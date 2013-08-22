/*
 * The MIT License
 *
 * Copyright 2013 Jesse Glick.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.compress_artifacts;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Run;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import jenkins.model.ArtifactManager;
import jenkins.util.VirtualFile;

final class CompressingArtifactManager extends ArtifactManager {

    private transient Run<?,?> build;

    CompressingArtifactManager(Run<?,?> build) {
        onLoad(build);
    }

    @Override public void onLoad(Run<?,?> build) {
        this.build = build;
    }

    @Override public void archive(FilePath workspace, Launcher launcher, BuildListener listener, Map<String,String> artifacts) throws IOException, InterruptedException {
        // TODO support updating entries
        OutputStream os = new FileOutputStream(archive());
        try {
            workspace.zip(os, new FilePath.ExplicitlySpecifiedDirScanner(artifacts));
        } finally {
            os.close();
        }
    }

    @Override public boolean delete() throws IOException, InterruptedException {
        return archive().delete();
    }

    @Override public VirtualFile root() {
        return new VF("");
    }

    private final class VF extends VirtualFile {

        private final String path;

        VF(String path) {
            this.path = path;
        }

        @Override public String getName() {
            return path.replaceFirst("^(.+/)?([^/]+)/?$", "$2");
        }

        @Override public URI toURI() {
            try {
                return new URI(null, path, null);
            } catch (URISyntaxException x) {
                throw new AssertionError(x);
            }
        }

        @Override public VirtualFile getParent() {
            return new VF(path.replaceFirst("(/?[^/]+)/?$", "$1"));
        }

        private boolean looksLikeDir() {
            return path.length() == 0 || path.endsWith("/");
        }

        @Override public boolean isDirectory() throws IOException {
            if (!looksLikeDir()) {
                return false;
            }
            ZipFile zf = new ZipFile(archive());
            try {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String p = entry.getName();
                    if (p.startsWith(path)) {
                        return true;
                    }
                }
                return false;
            } finally {
                zf.close();
            }
        }

        @Override public boolean isFile() throws IOException {
            if (looksLikeDir()) {
                return false;
            }
            ZipFile zf = new ZipFile(archive());
            try {
                return zf.getEntry(path) != null;
            } finally {
                zf.close();
            }
        }

        @Override public boolean exists() throws IOException {
            ZipFile zf = new ZipFile(archive());
            try {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String p = entry.getName();
                    if (looksLikeDir() ? p.startsWith(path) : p.equals(path)) {
                        return true;
                    }
                }
                return false;
            } finally {
                zf.close();
            }
        }

        @Override public VirtualFile[] list() throws IOException {
            if (!looksLikeDir()) {
                return new VirtualFile[0];
            }
            ZipFile zf = new ZipFile(archive());
            try {
                Set<VirtualFile> files = new HashSet<VirtualFile>();
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String p = entry.getName();
                    if (p.startsWith(path)) {
                        files.add(new VF(path + p.substring(path.length()).replaceFirst("/.+", "/")));
                    }
                }
                return files.toArray(new VirtualFile[files.size()]);
            } finally {
                zf.close();
            }
        }

        @Override public String[] list(String glob) throws IOException {
            throw new IOException("TODO not implemented");
        }

        @Override public VirtualFile child(String name) {
            // TODO this is ugly; would be better to not require / on path
            VF f = new VF(path + name + '/');
            try {
                if (f.isDirectory()) {
                    return f;
                }
            } catch (IOException x) {}
            return new VF(path + name);
        }

        @Override public long length() throws IOException {
            ZipFile zf = new ZipFile(archive());
            try {
                ZipEntry entry = zf.getEntry(path);
                return entry != null ? entry.getSize() : 0;
            } finally {
                zf.close();
            }
        }

        @Override public long lastModified() throws IOException {
            ZipFile zf = new ZipFile(archive());
            try {
                ZipEntry entry = zf.getEntry(path);
                return entry != null ? entry.getTime() : 0;
            } finally {
                zf.close();
            }
        }

        @Override public boolean canRead() throws IOException {
            return true;
        }

        @Override public InputStream open() throws IOException {
            if (looksLikeDir()) {
                throw new IOException();
            }
            final ZipFile zf = new ZipFile(archive());
            ZipEntry entry = zf.getEntry(path);
            if (entry == null) {
                zf.close();
                throw new FileNotFoundException(path);
            }
            return new FilterInputStream(zf.getInputStream(entry)) {
                @Override public void close() throws IOException {
                    zf.close();
                }
            };
        }
    }

    private File archive() {
        return new File(build.getRootDir(), "archive.zip");
    }

}
