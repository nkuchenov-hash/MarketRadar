package com.marketradar.mobile;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;

/**
 * Do not publish offline-LLM builds unless the pure-Java tokenizer gives
 * the same XLM-R token ids as the canonical HuggingFace tokenizer.
 */
public final class LocalModelTest {
    @Test public void identicalTitlesMustNotDependOnProbabilisticAi(){
        assertTrue(ProductIdentityGate.literalMatch(
            "Яндекс Станция Мини 3 Про",
            "Яндекс станция MINI 3 PRO"));
        assertTrue(ProductIdentityGate.literalMatch(
            "Яндекс Станция Мини 3 Про", "Яндекс-Станция Мини 3 Про"));
        assertTrue(ProductIdentityGate.literalMatch(
            "Яндекс Станция Мини 3 Про",
            "Умная колонка Станция Мини 3 Про с Алисой, Zigbee 18 Вт"));
        assertFalse(ProductIdentityGate.literalMatch(
            "Яндекс Станция Мини 3 Про",
            "Подставка для Станции Мини 3 Про"));
        assertFalse(ProductIdentityGate.literalMatch(
            "Яндекс Станция Мини 3 Про",
            "Умная колонка Станция Мини 3 Про Макс"));
        assertFalse(ProductIdentityGate.literalMatch(
            "Яндекс Станция Мини 3 Про",
            "Чехол для Яндекс Станции Мини 3 Про"));
        assertFalse(ProductIdentityGate.literalMatch(
            "Яндекс Станция Мини 3 Про",
            "Яндекс Станция Мини 3"));
        assertFalse(ProductIdentityGate.literalMatch(
            "Яндекс Станция Мини 3 Про",
            "Яндекс Станция Мини 3 Про Макс"));
    }
    @Test public void javaSentencePieceMatchesReferencePairs() throws Exception {
        File assets=new File("src/main/assets");
        File vocab=new File(assets,"model-vocab.bin");
        File golden=new File(assets,"local-nli-token-golden.tsv");
        assertTrue("Model vocabulary must be embedded in the APK",vocab.isFile());
        assertTrue("Golden encodings required",golden.isFile());
        UnigramTokenizer tokenizer;
        try(FileInputStream data=new FileInputStream(vocab)){
            tokenizer=new UnigramTokenizer(data);
        }
        int checked=0;
        try(BufferedReader reader=new BufferedReader(new FileReader(golden))){
            for(String line;(line=reader.readLine())!=null;){
                String[] columns=line.split("\\t",-1);
                assertEquals("Malformed reference",3,columns.length);
                String[] words=columns[2].split(",");
                int[] ids=tokenizer.encodePair(columns[0],columns[1],112);
                assertEquals("Wrong XLM-R token sequence length",words.length,ids.length);
                for(int i=0;i<ids.length;i++)
                    assertEquals("Wrong token at "+i+" in "+columns[0],
                        Integer.parseInt(words[i]),ids[i]);
                checked++;
            }
        }
        assertTrue("Need representative Russian and English examples",checked>=7);
    }
    @Test public void localModelScoresPlusProductIdentityRejectWrongItems() throws Exception {
        File golden=new File("src/main/assets/local-nli-score-golden.tsv");
        assertTrue("Actual ONNX NLI smoke scores are required",golden.isFile());
        int checked=0;
        try(BufferedReader reader=new BufferedReader(new FileReader(golden))){
            for(String line;(line=reader.readLine())!=null;){
                String[] values=line.split("\\t",-1);
                assertEquals(6,values.length);
                boolean expected=Boolean.parseBoolean(values[2]);
                boolean actual=ProductIdentityGate.confirms(
                    values[0],values[1],
                    Float.parseFloat(values[3]),
                    Float.parseFloat(values[4]),
                    Float.parseFloat(values[5]));
                assertEquals("Wrong classification for "+values[1],expected,actual);
                checked++;
            }
        }
        assertTrue(checked>=7);
    }
}
