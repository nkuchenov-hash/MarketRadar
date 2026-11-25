#!/usr/bin/env bash
# One-time installation of a scoped GitHub release channel. No root SSH key in CI.
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || { echo "Run as root"; exit 1; }
src="$(cd "$(dirname "$0")" && pwd)"
account=marketradar-deploy
key="$(awk '{for(i=1;i<NF;i++) if($i=="ssh-ed25519") print $i" "$(i+1)}' /home/marketradar-probe/.ssh/authorized_keys)"
[[ "$key" =~ ^ssh-ed25519\ [A-Za-z0-9+/=]+$ ]] || { echo "Existing dedicated SSH key not found"; exit 1; }
id "$account" >/dev/null 2>&1 || useradd --create-home --shell /bin/sh "$account"
install -d -o "$account" -g "$account" -m 0700 "/home/$account/.ssh"
printf 'restrict,command="/usr/bin/sudo -n /usr/local/sbin/marketradar-deploy" %s\n' "$key" > "/home/$account/.ssh/authorized_keys"
chown "$account:$account" "/home/$account/.ssh/authorized_keys"
chmod 0600 "/home/$account/.ssh/authorized_keys"
cat > /usr/local/sbin/marketradar-deploy <<'PY'
#!/usr/bin/python3 -I
# Fixed root helper. Accepts only text files for existing application mounts.
import fcntl, json, os, re, stat, subprocess, sys, tempfile, time
from pathlib import Path
if len(sys.argv)!=1:sys.exit(64)
os.umask(0o077)
lock=open("/run/lock/marketradar-ozon-probe.lock","a")
try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
except BlockingIOError:sys.exit(75)
raw=sys.stdin.buffer.read(524289)
if len(raw)>524288:sys.exit("Payload too large")
payload=json.loads(raw)
if not isinstance(payload,dict) or set(payload)!={"revision","files"}:sys.exit("Invalid payload")
revision=payload["revision"]
if not isinstance(revision,str) or not re.fullmatch("[a-f0-9]{40}",revision):sys.exit("Invalid revision")
files=payload["files"]
allowed={name:"/app/server/"+name for name in ("retail_fast.py","web_cloud.py","public_html.py","cloud_browser.py","mobile_cloud.py","agent.py","main.py")}
allowed.update({name:"/app/web/"+name for name in ("index.html","app.js","style.css")})
if not isinstance(files,dict) or not files or not set(files)<=set(allowed):sys.exit("File outside release allowlist")
container="marketradar-backend-1"
docker="/usr/bin/docker"
def run(*args,**kw):return subprocess.run(args,check=True,timeout=kw.pop("timeout",60),**kw)
info=json.loads(subprocess.check_output([docker,"inspect",container],timeout=15))[0]
mounts={x["Destination"]:x for x in info["Mounts"]}
root=Path("/opt/marketradar/deploy")
changes={}
for name,value in files.items():
    if not isinstance(value,str) or not value or len(value.encode())>150000 or "\0" in value:sys.exit("Invalid text file")
    if name.endswith(".py"):compile(value,name,"exec")
    if name=="index.html" and "MarketRadar" not in value:sys.exit("Invalid web page")
    m=mounts.get(allowed[name],{})
    p=Path(m.get("Source",""))
    if m.get("Type")!="bind" or m.get("RW") or p.name!=name or p.parent.parent!=root or not p.parent.name.startswith("retail-live-") or p.resolve()!=p:
        sys.exit("Unexpected live mount: "+name)
    st=p.stat()
    if not stat.S_ISREG(st.st_mode) or st.st_uid!=0 or st.st_mode&0o022:sys.exit("Unsafe mount permissions")
    old=p.read_bytes();new=value.encode()
    if old!=new:changes[p]=(old,new)
if not changes:
    print("DEPLOY UP TO DATE",revision);sys.exit(0)
backup=Path(tempfile.mkdtemp(prefix="managed-release-",dir=root))
for p,(old,new) in changes.items():(backup/p.name).write_bytes(old)
restart=any(p.suffix==".py" for p in changes)
def healthy():
    code='import urllib.request,json; assert json.load(urllib.request.urlopen("http://127.0.0.1:10000/health",timeout=3))["ok"]'
    for _ in range(25):
        try:
            run(docker,"exec",container,"python","-c",code,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=5)
            return
        except (subprocess.SubprocessError,OSError):time.sleep(2)
    raise RuntimeError("Backend health check failed")
try:
    # Preserve existing bind mount inodes and never run downloaded code as root.
    for p,(old,new) in changes.items():p.write_bytes(new)
    if restart:run(docker,"restart",container,timeout=90)
    healthy()
except BaseException:
    for p,(old,new) in changes.items():p.write_bytes(old)
    if restart:
        try:run(docker,"restart",container,timeout=90)
        except Exception:pass
    print("DEPLOY ROLLED BACK",revision,flush=True);raise
print("DEPLOY VERIFIED",revision,"files:",",".join(p.name for p in changes),flush=True)
PY
chmod 0755 /usr/local/sbin/marketradar-deploy
printf '%s\n' 'marketradar-deploy ALL=(root) NOPASSWD: /usr/local/sbin/marketradar-deploy ""' > /etc/sudoers.d/marketradar-deploy
chmod 0440 /etc/sudoers.d/marketradar-deploy
visudo -cf /etc/sudoers.d/marketradar-deploy
# Apply current access-code web frontend immediately on the first setup.
if [[ -s "$src/index.html" && -s "$src/app.js" ]]; then
    python3 - "$src" <<'BOOT' | /usr/local/sbin/marketradar-deploy
import json,sys,pathlib
p=pathlib.Path(sys.argv[1])
print(json.dumps({"revision":"e6bccb88ae5d5ba8cc650b4eaea42a057428213b","files":{n:(p/n).read_text(encoding="utf-8") for n in ("index.html","app.js")}},ensure_ascii=False))
BOOT
fi
echo "MANAGED DEPLOYMENT READY"
