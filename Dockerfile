FROM python:3.12-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 DB_PATH=/app/data/marketradar.sqlite3 PLAYWRIGHT_BROWSERS_PATH=/ms-playwright CLOUD_BROWSER_ENABLED=true DISPLAY=:99
COPY requirements.txt ./
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 xvfb xauth \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir -r requirements.txt \
    && python -m playwright install --with-deps chromium
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
CMD ["sh","-c","exec xvfb-run -a -s '-screen 0 1440x900x24' uvicorn server.main:app --host 0.0.0.0 --port ${PORT:-10000} --workers 1 --proxy-headers"]
