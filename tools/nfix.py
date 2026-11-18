
import sqlite3, shutil, os
base = r'E:\灵感构图\_palette_work\device_snapshot'
work = os.path.join(base,'nwork.db')
for f in (work, work+'-wal', work+'-shm'):
    if os.path.exists(f): os.remove(f)
shutil.copy(os.path.join(base,'n.db'), work)
w = os.path.join(base,'n.db-wal')
if os.path.exists(w): shutil.copy(w, work+'-wal')
con = sqlite3.connect(work); cur = con.cursor()
cur.execute("SELECT module_pkg_name, apk_path FROM modules WHERE module_pkg_name='com.palette.unlock'")
row = cur.fetchone(); print('modules:', row)
cur.execute("INSERT OR REPLACE INTO modules_state(module_pkg_name,user_id,enabled,scope_request_blocked) VALUES('com.palette.unlock',0,1,0)")
cur.execute("INSERT OR IGNORE INTO scope(module_pkg_name,app_pkg_name,user_id) VALUES('com.palette.unlock','com.oplus.camera',0)")
con.commit()
cur.execute("SELECT module_pkg_name,user_id,enabled FROM modules_state"); print('state:', cur.fetchall())
cur.execute("SELECT module_pkg_name,app_pkg_name FROM scope"); print('scope:', cur.fetchall())
cur.execute("PRAGMA wal_checkpoint(TRUNCATE)"); con.commit(); con.close()
out = os.path.join(base,'n_fixed.db'); shutil.copy(work, out)
for s in ('-wal','-shm'):
    p = work+s
    if os.path.exists(p):
        try: os.remove(p)
        except Exception: pass
print('out:', os.path.getsize(out))
