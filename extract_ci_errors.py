import zipfile
z = zipfile.ZipFile('ci_logs_7fa92c3.zip')
content = z.read('build/28_Build.txt').decode('utf-8', errors='replace')
for line in content.split('\n'):
    if ' e: ' in line:
        txt = line.split('Z ', 1)[1] if 'Z ' in line else line
        print(txt[:600])
