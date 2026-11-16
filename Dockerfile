FROM python:3.12-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 DB_PATH=/app/data/marketradar.sqlite3
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY server ./server
COPY web ./web
COPY extension ./extension
RUN mkdir -p /app/data /app/downloads && python -c "import zipfile,pathlib; p=pathlib.Path('/app/extension'); z=zipfile.ZipFile('/app/downloads/marketradar-extension.zip','w',compression=zipfile.ZIP_DEFLATED); [z.write(f, f.relative_to(p)) for f in p.rglob('*') if f.is_file()]; z.close()"
EXPOSE 10000
CMD ["sh","-c","exec uvicorn server.main:app --host 0.0.0.0 --port ${PORT:-10000} --workers 1 --proxy-headers"]
