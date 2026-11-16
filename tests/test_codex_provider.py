import asyncio
import json
import os
import sys
from pathlib import Path
from unittest.mock import patch
import pytest
from server import agent


def test_codex_requires_localhost(monkeypatch):
    monkeypatch.setenv('APP_ORIGIN','https://marketradar.example')
    with patch.object(agent,'codex_command',return_value=['codex']):
        ok, msg=agent.codex_status()
    assert not ok and 'локальном' in msg


def test_codex_not_installed(monkeypatch):
    monkeypatch.setenv('APP_ORIGIN','http://127.0.0.1:8765')
    with patch.object(agent,'codex_command',return_value=None):
        ok, msg=agent.codex_status()
    assert not ok and 'CONNECT_CODEX' in msg


@pytest.mark.skipif(sys.platform=='win32',reason='Unix fake CLI')
def test_codex_structured_output_without_api_key(monkeypatch,tmp_path):
    script=tmp_path/'codex'
    script.write_text('''#!/usr/bin/env python3
import json,os,sys
if sys.argv[1:3]==['login','status']:
    print('Logged in using ChatGPT')
    sys.exit(0)
if sys.argv[1:]==['--version']:
    print('codex-cli 0.169.0')
    sys.exit(0)
assert sys.argv[1]=='exec'
assert sys.argv[sys.argv.index('-m')+1]=='gpt-6.1-sol'
assert 'OPENAI_API_KEY' not in os.environ
assert '--sandbox' in sys.argv and sys.argv[sys.argv.index('--sandbox')+1]=='read-only'
assert '--output-schema' in sys.argv
source=sys.stdin.read()
assert 'станция' in source.lower()
path=sys.argv[sys.argv.index('--output-last-message')+1]
with open(path,'w',encoding='utf-8') as f:json.dump({'phrases':['Яндекс Станция Мини 3 Про'],'model_signature':'Мини 3 Про','exclusions':['Станция 3']},f,ensure_ascii=False)
''',encoding='utf-8')
    script.chmod(0o755)
    monkeypatch.setenv('APP_ORIGIN','http://127.0.0.1:8765')
    monkeypatch.setenv('MARKETRADAR_AI_PROVIDER','codex')
    monkeypatch.setenv('OPENAI_API_KEY','old-should-be-ignored')
    monkeypatch.setattr(agent,'codex_command',lambda:[str(script)])
    answer=asyncio.run(agent.plan('Яндекс Станция Мини 3 Про'))
    assert answer.model_signature=='Мини 3 Про'


def test_api_provider_requires_separate_explicit_setting(monkeypatch):
    monkeypatch.setenv('MARKETRADAR_AI_PROVIDER','api')
    monkeypatch.delenv('OPENAI_API_KEY',raising=False)
    ok,msg=agent.ai_status()
    assert not ok and 'API' in msg


def test_codex_version_preflight_rejects_older_cli(monkeypatch):
    from subprocess import CompletedProcess
    monkeypatch.setenv('APP_ORIGIN','http://127.0.0.1:8765')
    with patch.object(agent,'codex_command',return_value=['codex']), patch.object(
         agent.subprocess,'run',side_effect=[
             CompletedProcess(['codex','login','status'],0,'Logged in using ChatGPT',''),
             CompletedProcess(['codex','--version'],0,'codex-cli 0.120.0','')]):
        ok, msg=agent.codex_status()
    assert not ok and 'UPDATE_CODEX.bat' in msg and '0.120.0' in msg
