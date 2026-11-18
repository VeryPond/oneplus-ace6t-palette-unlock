import sys, os, hashlib, json
sys.path.insert(0, r'E:\灵感构图\tools')
from erofs2 import Erofs
import erofs_fmt6
class FR:
    def __init__(self, path):
        self.f=open(path,'rb'); self.size=os.path.getsize(path)
    def read(self, off, ln):
        self.f.seek(off); return self.f.read(ln)
r=FR(r'E:\灵感构图\gt8rom\odm.img'); e=Erofs(r)
def find(path):
    cur=e.root_nid
    for part in [p for p in path.split('/') if p]:
        info=e.inode(cur); hit=None
        for nm,nid,ft in e.listdir(info):
            if nm==part: hit=nid; break
        if hit is None: return None
        cur=hit
    return cur
def readf(path):
    nid=find(path)
    if nid is None: return None
    info=e.inode(nid)
    if info['fmt']==6: return erofs_fmt6.read_fmt6(r, info, 4096)
    return e.data(info)
OUT=r'E:\灵感构图\_palette_work\from_rom'
targets=[]
for ns in ['HDR','SDR']:
    for i in range(9): targets.append('/etc/camera/basictone/palette/%s/SC16Lut%d' % (ns,i))
for n in ['SimTool_Palette_Photo_Normal.ini','SimTool_Palette_Photo_Portrait.ini','SimTool_Palette_Photo_Night.ini','SimTool_Preview_Palette_Normal.ini']:
    targets.append('/etc/camera/basictone/setting/'+n)
for n in ['SimTool_Master.ini','SimTool_Dolby.ini','SimTool.ini','SimTool_Photo_Master.ini','SimTool_Photo_Normal.ini','FromXml']:
    targets.append('/etc/camera/basictone/setting/'+n)
manifest=[]
for t in targets:
    d=readf(t)
    rel=t.replace('/etc/camera/basictone/','')
    if d is None:
        print('MISSING', t); manifest.append({'path':t,'missing':True}); continue
    p=os.path.join(OUT, rel.replace('/','\\'))
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p,'wb').write(d)
    h=hashlib.sha256(d).hexdigest()
    print('%-58s %8d  %s' % (rel, len(d), h[:16]))
    manifest.append({'rel':rel,'size':len(d),'sha256':h})
json.dump(manifest, open(r'E:\灵感构图\_palette_work\rom_resources.json','w'), indent=1)
print('done', len(manifest))