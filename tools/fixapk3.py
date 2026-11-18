
import zipfile, os
src_apk = r'C:\palettebuild\aligned.apk'
tmp = r'C:\palettebuild\final_unsigned.apk'
xdir = r'E:\灵感构图\_palette_work\lsposed_src\META-INF\xposed'
extra = {
  'META-INF/xposed/module.prop': open(os.path.join(xdir,'module.prop'),'rb').read(),
  'META-INF/xposed/java_init.list': open(os.path.join(xdir,'java_init.list'),'rb').read(),
}
zin = zipfile.ZipFile(src_apk,'r')
items = []
for i in zin.infolist():
    items.append((i.filename, zin.read(i.filename), i.compress_type))
zin.close()
zout = zipfile.ZipFile(tmp,'w')
seen=set()
for n, data, ctype in items:
    if n in seen: continue
    if n.startswith('META-INF/') and ('PALETTE' in n or n=='META-INF/MANIFEST.MF'):
        continue
    seen.add(n)
    zi = zipfile.ZipInfo(n, date_time=(1980,1,1,0,0,0))
    # resources.arsc 必须 STORED，其余 DEFLATED
    if n == 'resources.arsc':
        zi.compress_type = zipfile.ZIP_STORED
    else:
        zi.compress_type = zipfile.ZIP_DEFLATED
    zi.external_attr = 0o644 << 16
    zout.writestr(zi, data)
for n, d in extra.items():
    if n in seen: continue
    seen.add(n)
    zi = zipfile.ZipInfo(n, date_time=(1980,1,1,0,0,0))
    zi.compress_type = zipfile.ZIP_DEFLATED
    zi.external_attr = 0o644 << 16
    zout.writestr(zi, d)
zout.close()
z = zipfile.ZipFile(tmp)
for i in z.infolist():
    print('  %-40s stored=%s' % (i.filename, i.compress_type==zipfile.ZIP_STORED))
