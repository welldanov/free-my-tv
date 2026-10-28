#!/usr/bin/env python3
"""Replace My Photos in filename order and restart its daily cycle (requires the daily-photos APK).

python3 launcher/tools/photo-cycle.py --serial 127.0.0.1:15555 Wallpapers/
python3 launcher/tools/photo-cycle.py --serial 127.0.0.1:15555 --restore backups/.../photos.zip

The previous collection is downloaded before any replacement. The TV validates all images before
publishing the new collection. The PC is only needed when changing the collection.
"""
import argparse
import datetime
import io
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import uuid
import zipfile

KINDS = {'.jpg', '.jpeg', '.png', '.webp', '.heic', '.heif'}
URI = 'content://io.freemytv.photos/'


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('images', nargs='*', type=Path, help='one folder, or images in the required order')
    parser.add_argument('--serial', required=True, help='connected ADB serial; see adb devices')
    parser.add_argument('--restore', type=Path, help='restore a photos.zip backup, including its cycle start date')
    parser.add_argument('--backup-dir', type=Path, help='new directory for the automatic backup')
    args = parser.parse_args()
    if bool(args.images) == bool(args.restore):
        parser.error('provide images/a folder OR --restore')
    if not shutil.which('adb'):
        parser.error('adb is not installed')
    images = args.images
    if len(images) == 1 and images[0].is_dir():
        images = sorted(p for p in images[0].iterdir() if p.is_file() and not p.name.startswith('.') and p.suffix.lower() in KINDS)
    if not args.restore and (not 1 <= len(images) <= 100 or any(not p.is_file() or p.suffix.lower() not in KINDS for p in images)):
        parser.error('expected 1–100 readable image files')
    if args.restore:
        with zipfile.ZipFile(args.restore) as archive:
            if archive.testzip() is not None:
                parser.error('damaged backup')
    backup = args.backup_dir or Path('backups') / ('photo-cycle-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f'))

    def adb(*command, **kwargs):
        result = subprocess.run(['adb', '-s', args.serial, *command], capture_output=True, **kwargs)
        if result.returncode or b'Error' in result.stderr or b'Exception' in result.stderr:
            raise RuntimeError((result.stderr + result.stdout).decode(errors='replace'))
        return result.stdout

    # Make a fresh provider process read current preferences and prevent a previous background load from winning.
    adb('shell', 'am', 'force-stop', 'io.freemytv')
    try:
        old = adb('exec-out', 'content', 'read', '--uri', URI + 'backup.zip')
        with zipfile.ZipFile(io.BytesIO(old)) as archive:
            if archive.testzip() is not None:
                raise RuntimeError('backup verification failed; collection was not changed')
        backup.mkdir(parents=True, exist_ok=False)
        (backup / 'photos.zip').write_bytes(old)
        (backup / 'RESTORE.txt').write_text(
            'Restore with the daily-photos APK still installed:\n'
            f'python3 launcher/tools/photo-cycle.py --serial {args.serial} --restore "{backup / "photos.zip"}"\n', encoding='utf-8')
        print(f'Backup: {backup / "photos.zip"}', flush=True)
        with tempfile.TemporaryDirectory(prefix='photo-cycle-') as temp:
            upload = args.restore
            if upload is None:
                upload = Path(temp) / 'collection.zip'
                with zipfile.ZipFile(upload, 'w', compression=zipfile.ZIP_STORED) as archive:
                    for i, path in enumerate(images, 1):
                        archive.write(path, f'{i:03d}{path.suffix.lower()}')
                        print(f'{i}: {path}', flush=True)
                (backup / 'new-order.txt').write_text('\n'.join(str(p.resolve()) for p in images) + '\n', encoding='utf-8')
            remote = '/data/local/tmp/freemytv-photos-' + uuid.uuid4().hex + '.zip'
            try:
                # Stage a regular file so content write reads a finite local stream.
                adb('push', str(upload), remote)
                response = adb('shell', f'content write --uri {URI}collection.zip?size={upload.stat().st_size} < {remote}')
                if b'Error' in response or b'Exception' in response:
                    raise RuntimeError(response.decode(errors='replace'))
            finally:
                adb('shell', 'rm', '-f', remote)
            # Android finalizes a completed transfer on its main handler, after content write returns.
            for _ in range(10):
                status = adb('shell', 'content', 'call', '--uri', URI, '--method', 'collection-status')
                if b'ready=true' in status:
                    break
                time.sleep(0.1)
            else:
                raise RuntimeError('upload was not finalized; old collection remains active')
            method = 'restore-collection' if args.restore else 'replace-collection'
            result = adb('shell', 'content', 'call', '--uri', URI, '--method', method)
            if b'success=true' not in result:
                raise RuntimeError(result.decode(errors='replace'))
            print(result.decode().strip())
    finally:
        adb('shell', 'am', 'force-stop', 'io.freemytv')
        adb('shell', 'am', 'start', '-n', 'io.freemytv/.HomeActivity')


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, zipfile.BadZipFile) as error:
        raise SystemExit(str(error))
