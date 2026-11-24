FROM python:3.12-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 DB_PATH=/app/data/marketradar.sqlite3 PLAYWRIGHT_BROWSERS_PATH=/ms-playwright CLOUD_BROWSER_ENABLED=true DISPLAY=:99
COPY requirements.txt ./
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 xvfb xauth \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir -r requirements.txt \
    && python -m playwright install --with-deps chromium
# Bundle the same pinned public offline model used by Android. The runtime
# needs no OpenAI key, model download, or external inference requests.
RUN pip install --no-cache-dir 'onnxruntime==1.31.1' 'tokenizers==0.22.1' 'numpy<3' \
    && mkdir -p /app/identity-model \
    && python -c "import urllib.request,pathlib; base='https://huggingface.co/onnx-community/multilingual-MiniLMv2-L6-mnli-xnli-ONNX/resolve/ca5daf3/'; root=pathlib.Path('/app/identity-model'); urllib.request.urlretrieve(base+'onnx/model_quantized.onnx', root/'model.onnx'); urllib.request.urlretrieve(base+'tokenizer.json', root/'tokenizer.json'); assert (root/'model.onnx').stat().st_size>90000000" \
    && chmod -R a+rX /app/identity-model
# The VPS bootstrap runs with umask 077: Git can create 0600 files and
# 0700 directories. Docker COPY preserves those modes; the unprivileged
# radar user must be able to traverse and read application code/assets.
COPY server ./server
COPY web ./web
COPY extension ./extension
RUN chmod -R a+rX /app/server /app/web /app/extension
RUN mkdir -p /app/data /app/downloads && python -c "import zipfile,pathlib; p=pathlib.Path('/app/extension'); z=zipfile.ZipFile('/app/downloads/marketradar-extension.zip','w',compression=zipfile.ZIP_DEFLATED); [z.write(f, f.relative_to(p)) for f in p.rglob('*') if f.is_file()]; z.close()" && useradd -m -u 10001 radar && chown -R radar:radar /app/data /app/downloads /ms-playwright
USER radar
EXPOSE 10000
# Keep API startup independent of the virtual display. If Chromium's Xvfb
# fails, /health and the authenticated job status endpoint remain available.
CMD ["sh","-c","Xvfb :99 -screen 0 1440x900x24 -nolisten tcp >/tmp/marketradar-xvfb.log 2>&1 & exec uvicorn server.main:app --host 0.0.0.0 --port ${PORT:-10000} --workers 1 --proxy-headers"]
