package com.marketradar.mobile;

import android.content.Context;
import android.os.Handler;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * On-device semantic product verification. No account tokens, network access,
 * server URLs or API keys. Model is shipped INSIDE the signed APK.
 *
 * Model: multilingual-MiniLMv2-L6-mnli-xnli-ONNX, int8 (MIT license).
 * 0 entailment, 1 neutral, 2 contradiction.
 */
final class LocalModelVerifier {
    interface Callback {
        void verified(List<ScanEngine.Offer> confirmed,String error);
    }

    private static volatile LocalModelVerifier singleton;
    private static final Object LOCK=new Object();
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final UnigramTokenizer tokenizer;

    private LocalModelVerifier(Context context) throws Exception {
        Context app=context.getApplicationContext();
        File modelFile=new File(app.getFilesDir(),"nli-model-v1.onnx");
        if(!modelFile.isFile()||modelFile.length()<90_000_000L){
            File temp=new File(app.getFilesDir(),"nli-model-v1.tmp");
            try(InputStream source=app.getAssets().open("model-identity-int8.onnx");
                FileOutputStream target=new FileOutputStream(temp)){
                byte[] chunk=new byte[65536];
                int count;
                while((count=source.read(chunk))!=-1)target.write(chunk,0,count);
            }
            if(temp.length()<90_000_000L||!temp.renameTo(modelFile))
                throw new IllegalStateException("Missing or truncated embedded AI model");
        }
        try(InputStream vocabulary=app.getAssets().open("model-vocab.bin")){
            tokenizer=new UnigramTokenizer(vocabulary);
        }
        environment=OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options=new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(2);
        options.setInterOpNumThreads(1);
        session=environment.createSession(modelFile.getAbsolutePath(),options);
        options.close();
    }

    static LocalModelVerifier get(Context context) throws Exception {
        LocalModelVerifier instance=singleton;
        if(instance!=null)return instance;
        synchronized(LOCK){
            instance=singleton;
            if(instance==null){
                instance=new LocalModelVerifier(context);
                singleton=instance;
            }
        }
        return instance;
    }

    /**
     * Verify all three competing semantic hypotheses in ONE ONNX inference
     * with a batch of 3 padded input sequences. The previous code executed
     * the same model separately three times per product, multiplying CPU and
     * latency by approximately three.
     */
    private float[] identityEvidence(String query,String title) throws Exception {
        String premise="В продаже: "+title+".";
        String[] hypotheses={
            "Продаваемый предмет — это "+query+".",
            "Продаваемый предмет является аксессуаром или запчастью для "+query+".",
            "Продаваемый предмет является другим товаром, не "+query+"."
        };
        int[][] sequences=new int[hypotheses.length][];
        int length=0;
        for(int i=0;i<hypotheses.length;i++){
            sequences[i]=tokenizer.encodePair(premise,hypotheses[i],112);
            length=Math.max(length,sequences[i].length);
        }
        long[] ids=new long[hypotheses.length*length];
        long[] mask=new long[ids.length];
        java.util.Arrays.fill(ids,1L); // XLM-R pad token ID
        for(int i=0;i<hypotheses.length;i++){
            for(int j=0;j<sequences[i].length;j++){
                ids[i*length+j]=sequences[i][j];
                mask[i*length+j]=1L;
            }
        }
        try(OnnxTensor input=OnnxTensor.createTensor(environment,
                java.nio.LongBuffer.wrap(ids),new long[]{hypotheses.length,length});
            OnnxTensor attention=OnnxTensor.createTensor(environment,
                java.nio.LongBuffer.wrap(mask),new long[]{hypotheses.length,length})){
            Map<String,OnnxTensor> tensors=new HashMap<>();
            tensors.put("input_ids",input);
            tensors.put("attention_mask",attention);
            try(OrtSession.Result results=session.run(tensors)){
                Object raw=results.get(0).getValue();
                if(!(raw instanceof float[][]))
                    throw new IllegalStateException("Unexpected model output");
                float[][] scores=(float[][])raw;
                if(scores.length!=hypotheses.length)
                    throw new IllegalStateException("Wrong ONNX batch size");
                float[] evidence=new float[hypotheses.length];
                for(int i=0;i<scores.length;i++){
                    float[] values=scores[i];
                    if(values.length!=3)
                        throw new IllegalStateException("Wrong NLI label count");
                    double max=Math.max(values[0],Math.max(values[1],values[2]));
                    double a=Math.exp(values[0]-max);
                    double b=Math.exp(values[1]-max);
                    double c=Math.exp(values[2]-max);
                    evidence[i]=(float)(a/(a+b+c));
                }
                return evidence;
            }
        }
    }

    boolean identical(String query,String title) throws Exception {
        // Skip expensive NLI when the model/edition clearly differs.
        if(!ScanEngine.exact(query,title))return false;
        float[] scores=identityEvidence(query,title);
        return ProductIdentityGate.confirms(query,title,scores[0],scores[1],scores[2]);
    }

    static void verify(Context context,String query,List<ScanEngine.Offer> offers,
            Handler ui,Callback callback){
        new Thread(()->{
            try{
                LocalModelVerifier verifier=get(context);
                ArrayList<ScanEngine.Offer> selected=new ArrayList<>();
                Map<String,Boolean> cache=new HashMap<>();
                for(ScanEngine.Offer offer:offers){
                    if(Thread.currentThread().isInterrupted())break;
                    if(offer.price<=0)continue;
                    String normalized=ScanEngine.norm(offer.title);
                    Boolean confirmed=cache.get(normalized);
                    if(confirmed==null){
                        confirmed=verifier.identical(query,offer.title);
                        cache.put(normalized,confirmed);
                    }
                    if(confirmed)selected.add(offer);
                }
                ui.post(()->callback.verified(selected,null));
            }catch(Exception error){
                ui.post(()->callback.verified(new ArrayList<>(),
                    "Локальная ИИ-модель не запустилась. Цены не проверены."));
            }
        },"MarketRadar-Offline-NLI").start();
    }
}
