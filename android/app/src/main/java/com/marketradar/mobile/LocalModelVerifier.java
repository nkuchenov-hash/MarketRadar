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
    private final Map<String,Boolean> sessionDecisions=
        new java.util.LinkedHashMap<String,Boolean>(256,0.75f,true){
            @Override protected boolean removeEldestEntry(Map.Entry<String,Boolean> eldest){
                return size()>1024;
            }
        };

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

    private float entailment(String premise,String hypothesis) throws Exception {
        int[] tokens=tokenizer.encodePair(premise,hypothesis,112);
        long[] ids=new long[tokens.length],mask=new long[tokens.length];
        for(int i=0;i<tokens.length;i++){ids[i]=tokens[i];mask[i]=1;}
        try(OnnxTensor input=OnnxTensor.createTensor(environment,
                java.nio.LongBuffer.wrap(ids),new long[]{1,ids.length});
            OnnxTensor attention=OnnxTensor.createTensor(environment,
                java.nio.LongBuffer.wrap(mask),new long[]{1,mask.length})){
            Map<String,OnnxTensor> tensors=new HashMap<>();
            tensors.put("input_ids",input);
            tensors.put("attention_mask",attention);
            try(OrtSession.Result results=session.run(tensors)){
                Object raw=results.get(0).getValue();
                if(!(raw instanceof float[][]))
                    throw new IllegalStateException("Unexpected NLI model output");
                float[] scores=((float[][])raw)[0];
                if(scores.length!=3)
                    throw new IllegalStateException("Wrong NLI label count");
                double max=Math.max(scores[0],Math.max(scores[1],scores[2]));
                double a=Math.exp(scores[0]-max);
                double b=Math.exp(scores[1]-max);
                double c=Math.exp(scores[2]-max);
                return (float)(a/(a+b+c));
            }
        }
    }

    synchronized boolean identical(String query,String title) throws Exception {
        if(ProductIdentityGate.literalMatch(query,title))return true;
        if(!ScanEngine.exact(query,title))return false;
        String key=ScanEngine.norm(query)+"\u0001"+ScanEngine.norm(title);
        Boolean cached=sessionDecisions.get(key);
        if(cached!=null)return cached;
        String premise="В продаже: "+title+".";
        float same=entailment(premise,"Продаваемый предмет — это "+query+".");
        float accessory=entailment(premise,
            "Продаваемый предмет является аксессуаром или запчастью для "+query+".");
        float different=entailment(premise,
            "Продаваемый предмет является другим товаром, не "+query+".");
        boolean accepted=ProductIdentityGate.confirms(query,title,same,accessory,different);
        sessionDecisions.put(key,accepted);
        return accepted;
    }

    static void verify(Context context,String query,List<ScanEngine.Offer> offers,
            Handler ui,Callback callback){
        new Thread(()->{
            ArrayList<ScanEngine.Offer> selected=new ArrayList<>();
            try{
                // Exact normalized titles never depend on model startup or
                // probabilistic NLI scores.
                LocalModelVerifier verifier=null;
                Map<String,Boolean> cache=new HashMap<>();
                for(ScanEngine.Offer offer:offers){
                    if(Thread.currentThread().isInterrupted())break;
                    if(offer.price<=0)continue;
                    String normalized=ScanEngine.norm(offer.title);
                    Boolean confirmed=cache.get(normalized);
                    if(confirmed==null){
                        if(ProductIdentityGate.literalMatch(query,offer.title)){
                            confirmed=true;
                        }else{
                            if(verifier==null)verifier=get(context);
                            confirmed=verifier.identical(query,offer.title);
                        }
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
