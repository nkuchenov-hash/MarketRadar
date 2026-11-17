package com.marketradar.mobile;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline SentencePiece Unigram tokenizer for XLM-R multilingual NLI.
 * Input vocabulary is a compact build-time conversion of public tokenizer.json.
 * Keeps the tokens and scores intact (not an invented token dictionary).
 */
final class UnigramTokenizer {
    private static final int BOS=0, EOS=2, UNK=3;
    private static final int MAX_CHARS=19;
    private final Map<String,Piece> pieces=new HashMap<>(300000);

    static final class Piece {
        final int id;
        final double score;
        Piece(int id,double score){this.id=id;this.score=score;}
    }
    UnigramTokenizer(InputStream input) throws IOException {
        try(DataInputStream data=new DataInputStream(new BufferedInputStream(input,65536))){
            byte[] magic=new byte[4];
            data.readFully(magic);
            if(magic[0]!='M'||magic[1]!='R'||magic[2]!='U'||magic[3]!='1')
                throw new IOException("Invalid embedded tokenizer vocabulary");
            int count=data.readInt();
            if(count<200000||count>300000)throw new IOException("Bad tokenizer size");
            for(int i=0;i<count;i++){
                int length=data.readUnsignedShort();
                if(length>4000)throw new IOException("Bad tokenizer token");
                byte[] text=new byte[length];
                data.readFully(text);
                float score=data.readFloat();
                pieces.put(new String(text,java.nio.charset.StandardCharsets.UTF_8),
                           new Piece(i,score));
            }
        }
    }

    int[] encodePair(String premise,String hypothesis,int maxLength){
        int max=Math.min(160,Math.max(24,maxLength));
        // XLM-R pair format: <s> A </s> </s> B </s>.
        List<Integer> a=encode(premise);
        List<Integer> b=encode(hypothesis);
        while(a.size()+b.size()>max-4){
            if(a.size()>b.size())a.remove(a.size()-1);
            else if(!b.isEmpty())b.remove(b.size()-1);
            else break;
        }
        int[] out=new int[a.size()+b.size()+4];
        int idx=0;out[idx++]=BOS;
        for(int id:a)out[idx++]=id;
        out[idx++]=EOS;out[idx++]=EOS;
        for(int id:b)out[idx++]=id;
        out[idx]=EOS;
        return out;
    }

    List<Integer> encode(String text){
        ArrayList<Integer> out=new ArrayList<>();
        if(text==null||text.isBlank())return out;
        // XLM-R tokenizer.json: WhitespaceSplit followed by Metaspace
        // (replacement U+2581, prepend_scheme always), and a precompiled
        // SentencePiece normalizer. NFKC is an approximation for rare Unicode
        // normalizer mappings; validation uses Russian/English product names.
        String normalized=Normalizer.normalize(text,Normalizer.Form.NFKC).trim();
        if(normalized.isEmpty())return out;
        for(String word:normalized.split("\\s+")){
            if(!word.isEmpty())segment("▁"+word,out);
        }
        return out;
    }

    private void segment(String input,List<Integer> output){
        int n=input.length();
        double[] best=new double[n+1];
        int[] previous=new int[n+1], tokenIds=new int[n+1];
        java.util.Arrays.fill(best,Double.NEGATIVE_INFINITY);
        java.util.Arrays.fill(previous,-1);
        best[0]=0;
        for(int i=0;i<n;i++){
            if(previous[i]<0&&i!=0)continue;
            int limit=Math.min(n,i+MAX_CHARS);
            for(int end=i+1;end<=limit;end++){
                // Do not break up surrogate pairs.
                if(end<n&&Character.isHighSurrogate(input.charAt(end-1)) &&
                    Character.isLowSurrogate(input.charAt(end)))continue;
                Piece piece=pieces.get(input.substring(i,end));
                if(piece==null)continue;
                double v=best[i]+piece.score;
                if(v>best[end]){
                    best[end]=v;previous[end]=i;tokenIds[end]=piece.id;
                }
            }
            int cp=Character.codePointAt(input,i);
            int end=i+Character.charCount(cp);
            if(previous[end]==-1){
                double v=best[i]-100;
                if(v>best[end]){best[end]=v;previous[end]=i;tokenIds[end]=UNK;}
            }
        }
        if(previous[n]<0){output.add(UNK);return;}
        ArrayList<Integer> backwards=new ArrayList<>();
        for(int cursor=n;cursor>0;cursor=previous[cursor]){
            if(previous[cursor]<0){output.add(UNK);return;}
            backwards.add(tokenIds[cursor]);
        }
        for(int i=backwards.size()-1;i>=0;i--)output.add(backwards.get(i));
    }
}
