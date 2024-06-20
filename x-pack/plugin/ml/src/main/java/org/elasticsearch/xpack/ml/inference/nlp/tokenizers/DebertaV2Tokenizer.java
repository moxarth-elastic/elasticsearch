/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 *
 * This Java port DeBERTa-V2 tokenizer, was derived from
 * Microsoft's DeBERTa-V2 project at https://github.com/microsoft/DeBERTa
 * and
 * Huggingface's DeBERTa-V2 transformers
 * project at https://github.com/huggingface/transformers/blob/main/src/transformers/models/deberta_v2/tokenization_deberta_v2.py
 */

package org.elasticsearch.xpack.ml.inference.nlp.tokenizers;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.DebertaV2Tokenization;
import org.elasticsearch.xpack.core.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.ml.inference.nlp.NlpTask;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DebertaV2Tokenizer extends NlpTokenizer {

    public static final String UNKNOWN_TOKEN = "[UNK]";
    public static final String SEPARATOR_TOKEN = "[SEP]";
    public static final String PAD_TOKEN = "[PAD]";
    public static final String CLASS_TOKEN = "[CLS]";
    public static final String MASK_TOKEN = "[MASK]";

    private static final Set<String> NEVER_SPLIT = Set.of(UNKNOWN_TOKEN, SEPARATOR_TOKEN, PAD_TOKEN, CLASS_TOKEN, MASK_TOKEN);// TODO verify
                                                                                                                              // these all
                                                                                                                              // need to be
                                                                                                                              // never split

    private final DebertaAnalyzer debertaAnalyzer;
    protected final List<String> originalVocab;
    private final SortedMap<String, Integer> vocab;
    protected final boolean withSpecialTokens;
    protected final int sepTokenId;
    private final int clsTokenId;
    protected final int padTokenId;
    private final int maxSequenceLength;

    protected DebertaV2Tokenizer(
        List<String> originalVocab,
        SortedMap<String, Integer> vocab,
        List<Double> scores,
        boolean withSpecialTokens,
        int maxSequenceLength,
        Set<String> neverSplit
    ) throws IOException {
        this.originalVocab = originalVocab;
        this.debertaAnalyzer = new DebertaAnalyzer(
            originalVocab,
            scores,
            new ArrayList<>(Sets.union(NEVER_SPLIT, neverSplit)),
            UNKNOWN_TOKEN
        );
        this.vocab = vocab;
        this.withSpecialTokens = withSpecialTokens;
        this.maxSequenceLength = maxSequenceLength;
        if (vocab.containsKey(UNKNOWN_TOKEN) == false) {
            throw ExceptionsHelper.conflictStatusException("stored vocabulary is missing required [{}] token", UNKNOWN_TOKEN);
        }
        if (vocab.containsKey(PAD_TOKEN) == false) {
            throw ExceptionsHelper.conflictStatusException("stored vocabulary is missing required [{}] token", PAD_TOKEN);
        }
        this.padTokenId = vocab.get(PAD_TOKEN);
        if (withSpecialTokens) {
            Set<String> missingSpecialTokens = Sets.difference(Set.of(SEPARATOR_TOKEN, CLASS_TOKEN), vocab.keySet());
            if (missingSpecialTokens.isEmpty() == false) {
                throw ExceptionsHelper.conflictStatusException("stored vocabulary is missing required {} token(s)", missingSpecialTokens);
            }
            this.sepTokenId = vocab.get(SEPARATOR_TOKEN);
            this.clsTokenId = vocab.get(CLASS_TOKEN);
        } else {
            this.sepTokenId = -1;
            this.clsTokenId = -1;
        }
    }

    @Override
    int clsTokenId() {
        return clsTokenId;
    }

    @Override
    int sepTokenId() {
        return sepTokenId;
    }

    @Override
    int maxSequenceLength() {
        return maxSequenceLength;
    }

    @Override
    boolean isWithSpecialTokens() {
        return withSpecialTokens;
    }

    @Override
    int numExtraTokensForSingleSequence() {
        assert false;
        return -1; // TODO what is this?
    }

    @Override
    int getNumExtraTokensForSeqPair() {
        assert false;
        return -1; // TODO what is this?
    }

    @Override
    int defaultSpanForChunking(int maxWindowSize) {
        return (maxWindowSize - numExtraTokensForSingleSequence()) / 2;
    }

    @Override
    public TokenizationResult buildTokenizationResult(List<TokenizationResult.Tokens> tokenizations) {
        return new DebertaTokenizationResult(originalVocab, tokenizations, padTokenId);
    }

    @Override
    public NlpTask.RequestBuilder requestBuilder() {
        return (inputs, requestId, truncate, span, windowSize) -> buildTokenizationResult(
            IntStream.range(0, inputs.size())
                .boxed()
                .flatMap(seqId -> tokenize(inputs.get(seqId), truncate, span, seqId, windowSize).stream())
                .collect(Collectors.toList())
        ).buildRequest(requestId, truncate);
    }

    @Override
    public OptionalInt getPadTokenId() {
        return OptionalInt.of(padTokenId);
    }

    @Override
    public String getPadToken() {
        return PAD_TOKEN;
    }

    @Override
    public OptionalInt getMaskTokenId() {
        Integer maskId = vocab.get(MASK_TOKEN);
        if (maskId == null) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(maskId);
    }

    @Override
    public String getMaskToken() {
        return MASK_TOKEN;
    }

    @Override
    public List<String> getVocabulary() {
        return originalVocab;
    }

    @Override
    TokenizationResult.TokensBuilder createTokensBuilder(int clsTokenId, int sepTokenId, boolean withSpecialTokens) {
        return new DebertaTokenizationResult.DebertaTokensBuilder(clsTokenId, sepTokenId, withSpecialTokens);
    }

    public static DebertaV2Tokenizer.Builder builder(List<String> vocab, List<Double> scores, DebertaV2Tokenization tokenization) {
        return new DebertaV2Tokenizer.Builder(vocab, scores, tokenization);
    }

    public static class Builder {

        protected final List<String> originalVocab;
        protected final List<Double> scores;
        protected final SortedMap<String, Integer> vocab;
        protected boolean withSpecialTokens;
        protected int maxSequenceLength;
        protected Set<String> neverSplit;

        protected Builder(List<String> vocab, List<Double> scores, DebertaV2Tokenization tokenization) {
            this.originalVocab = vocab;
            this.vocab = buildSortedVocab(vocab);
            this.scores = scores;
            this.withSpecialTokens = tokenization.withSpecialTokens();
            this.maxSequenceLength = tokenization.maxSequenceLength();
        }

        private static SortedMap<String, Integer> buildSortedVocab(List<String> vocab) {
            SortedMap<String, Integer> sortedVocab = new TreeMap<>();
            for (int i = 0; i < vocab.size(); i++) {
                sortedVocab.put(vocab.get(i), i);
            }
            return sortedVocab;
        }

        public DebertaV2Tokenizer.Builder setNeverSplit(Set<String> neverSplit) {
            this.neverSplit = neverSplit;
            return this;
        }

        public DebertaV2Tokenizer.Builder setMaxSequenceLength(int maxSequenceLength) {
            this.maxSequenceLength = maxSequenceLength;
            return this;
        }

        /**
         * Include CLS and SEP tokens
         * @param withSpecialTokens if true include CLS and SEP tokens
         * @return this
         */
        public DebertaV2Tokenizer.Builder setWithSpecialTokens(boolean withSpecialTokens) {
            this.withSpecialTokens = withSpecialTokens;
            return this;
        }

        public DebertaV2Tokenizer build() throws IOException {
            if (neverSplit == null) {
                neverSplit = Collections.emptySet();
            }

            return new DebertaV2Tokenizer(originalVocab, vocab, scores, withSpecialTokens, maxSequenceLength, neverSplit);
        }
    }

    @Override
    public InnerTokenization innerTokenize(String seq) {
        List<Integer> tokenPositionMap = new ArrayList<>();
        try (TokenStream ts = debertaAnalyzer.tokenStream("input", seq)) {
            ts.reset();
            PositionIncrementAttribute tokenPos = ts.addAttribute(PositionIncrementAttribute.class);
            int currPos = -1;
            while (ts.incrementToken()) {
                currPos += tokenPos.getPositionIncrement();
                tokenPositionMap.add(currPos);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new InnerTokenization(new ArrayList<>(debertaAnalyzer.getTokens()), tokenPositionMap);
    }

    @Override
    public void close() {
        this.debertaAnalyzer.close();
    }

    static class DebertaAnalyzer extends Analyzer {
        private final List<String> vocabulary;
        private final List<String> neverSplit;
        private final double[] scores;
        private UnigramTokenizer innerTokenizer;
        private final String unknownToken;
        private final PrecompiledCharMapNormalizer.Config normalizer;

        DebertaAnalyzer(List<String> vocabulary, List<Double> scores, List<String> neverSplit, String unknownToken) throws IOException {
            this.vocabulary = vocabulary;
            this.neverSplit = neverSplit;
            this.unknownToken = unknownToken;
            this.scores = new double[scores.size()];
            int i = 0;
            for (Double s : scores) {
                this.scores[i++] = s;
            }
            normalizer = PrecompiledCharMapNormalizer.fromBase64EncodedResource(
                // TODO verify this is correct
                "/org/elasticsearch/xpack/ml/inference.nlp.tokenizers/spm_precompiled_normalizer.txt"
            );
        }

        @Override
        protected Reader initReader(String fieldName, Reader reader) {
            if (normalizer.offsets().length > 0) {
                return new PrecompiledCharMapNormalizer(normalizer.offsets(), normalizer.utf8str(), reader);
            }
            return reader;
        }

        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            this.innerTokenizer = UnigramTokenizer.build(neverSplit, vocabulary, scores, unknownToken);
            return new TokenStreamComponents(this.innerTokenizer);
        }

        public List<DelimitedToken.Encoded> getTokens() {
            if (innerTokenizer != null) {
                return innerTokenizer.getTokenizedValues();
            } else {
                return List.of();
            }
        }
    }
}
