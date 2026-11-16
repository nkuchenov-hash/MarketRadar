import secrets, hashlib, hmac, time

def hash_password(password: str, salt: str|None=None) -> str:
    salt = salt or secrets.token_hex(16)
    raw=hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), n=2**14,r=8,p=1,dklen=32)
    return f'scrypt${salt}${raw.hex()}'

def verify_password(password:str, hashed:str)->bool:
    try:
        _,salt,_=hashed.split('$')
        return hmac.compare_digest(hash_password(password,salt),hashed)
    except Exception:return False

def opaque_token(n=32):return secrets.token_urlsafe(n)
def digest(secret):return hashlib.sha256(secret.encode()).hexdigest()
def valid_until(minutes=30):return int(time.time()+minutes*60)
