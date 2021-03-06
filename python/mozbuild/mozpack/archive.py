# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from __future__ import absolute_import

import bz2
import gzip
import stat
import tarfile


# 2016-01-01T00:00:00+0000
DEFAULT_MTIME = 1451606400


def create_tar_from_files(fp, files):
    """Create a tar file deterministically.

    Receives a dict mapping names of files in the archive to local filesystem
    paths.

    The files will be archived and written to the passed file handle opened
    for writing.

    Only regular files can be written.

    FUTURE accept mozpack.files classes for writing
    FUTURE accept a filename argument (or create APIs to write files)
    """
    with tarfile.open(name='', mode='w', fileobj=fp, dereference=True) as tf:
        for archive_path, fs_path in sorted(files.items()):
            ti = tf.gettarinfo(fs_path, archive_path)

            if not ti.isreg():
                raise ValueError('not a regular file: %s' % fs_path)

            # Disallow setuid and setgid bits. This is an arbitrary restriction.
            # However, since we set uid/gid to root:root, setuid and setgid
            # would be a glaring security hole if the archive were
            # uncompressed as root.
            if ti.mode & (stat.S_ISUID | stat.S_ISGID):
                raise ValueError('cannot add file with setuid or setgid set: '
                                 '%s' % fs_path)

            # Set uid, gid, username, and group as deterministic values.
            ti.uid = 0
            ti.gid = 0
            ti.uname = ''
            ti.gname = ''

            # Set mtime to a constant value.
            ti.mtime = DEFAULT_MTIME

            with open(fs_path, 'rb') as fh:
                tf.addfile(ti, fh)


def create_tar_gz_from_files(fp, files, filename=None, compresslevel=9):
    """Create a tar.gz file deterministically from files.

    This is a glorified wrapper around ``create_tar_from_files`` that
    adds gzip compression.

    The passed file handle should be opened for writing in binary mode.
    When the function returns, all data has been written to the handle.
    """
    # Offset 3-7 in the gzip header contains an mtime. Pin it to a known
    # value so output is deterministic.
    gf = gzip.GzipFile(filename=filename or '', mode='wb', fileobj=fp,
                       compresslevel=compresslevel, mtime=DEFAULT_MTIME)
    with gf:
        create_tar_from_files(gf, files)


class _BZ2Proxy(object):
    """File object that proxies writes to a bz2 compressor."""
    def __init__(self, fp, compresslevel=9):
        self.fp = fp
        self.compressor = bz2.BZ2Compressor(compresslevel=compresslevel)
        self.pos = 0

    def tell(self):
        return self.pos

    def write(self, data):
        data = self.compressor.compress(data)
        self.pos += len(data)
        self.fp.write(data)

    def close(self):
        data = self.compressor.flush()
        self.pos += len(data)
        self.fp.write(data)


def create_tar_bz2_from_files(fp, files, compresslevel=9):
    """Create a tar.bz2 file deterministically from files.

    This is a glorified wrapper around ``create_tar_from_files`` that
    adds bzip2 compression.

    This function is similar to ``create_tar_gzip_from_files()``.
    """
    proxy = _BZ2Proxy(fp, compresslevel=compresslevel)
    create_tar_from_files(proxy, files)
    proxy.close()
