package fr.tibkpl.openut2004;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Affiche l'image de l'ecran X virtuel (Xvfb -fbdir) : le fichier XWD est mappe en memoire
 * (partage avec Xvfb) et envoye au GPU a chaque rafraichissement, mis a l'echelle de l'ecran
 * en conservant le format d'image.
 */
final class FramebufferView extends GLSurfaceView {

    private final FbRenderer renderer;

    FramebufferView(Context context, File framebufferFile) {
        super(context);
        setEGLContextClientVersion(2);
        renderer = new FbRenderer(framebufferFile);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setFocusable(false);
    }

    private static final class FbRenderer implements Renderer {
        private static final String VERTEX =
                "attribute vec2 aPos; attribute vec2 aTex; varying vec2 vTex;\n"
                + "void main() { gl_Position = vec4(aPos, 0.0, 1.0); vTex = aTex; }";
        // Xvfb 24 bits stocke les pixels en BGRX : on remet les canaux dans l'ordre.
        private static final String FRAGMENT =
                "precision mediump float; varying vec2 vTex; uniform sampler2D uTex;\n"
                + "void main() { gl_FragColor = vec4(texture2D(uTex, vTex).bgr, 1.0); }";

        private final File file;
        private MappedByteBuffer mapped;
        private ByteBuffer pixels;
        private int fbWidth;
        private int fbHeight;
        private int viewWidth;
        private int viewHeight;
        private int program;
        private int texture;
        private boolean textureAllocated;
        private FloatBuffer quad;
        private long lastAttempt;

        FbRenderer(File file) {
            this.file = file;
        }

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            program = buildProgram();
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            texture = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            textureAllocated = false;
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int width, int height) {
            viewWidth = width;
            viewHeight = height;
            GLES20.glViewport(0, 0, width, height);
            quad = null;
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (pixels == null && !openFramebuffer()) return;

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            pixels.position(0);
            if (!textureAllocated) {
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fbWidth, fbHeight, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
                textureAllocated = true;
            } else {
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, fbWidth, fbHeight,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
            }

            if (quad == null) quad = buildQuad();
            GLES20.glUseProgram(program);
            int aPos = GLES20.glGetAttribLocation(program, "aPos");
            int aTex = GLES20.glGetAttribLocation(program, "aTex");
            quad.position(0);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aPos);
            quad.position(2);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        /** Mappe le fichier XWD une fois que Xvfb l'a cree. */
        private boolean openFramebuffer() {
            long now = System.currentTimeMillis();
            if (now - lastAttempt < 250) return false;
            lastAttempt = now;
            if (!file.isFile() || file.length() < 100) return false;
            try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                 FileChannel channel = raf.getChannel()) {
                mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
                mapped.order(ByteOrder.BIG_ENDIAN);
                int headerSize = mapped.getInt(0);
                int width = mapped.getInt(16);
                int height = mapped.getInt(20);
                int bitsPerPixel = mapped.getInt(44);
                int bytesPerLine = mapped.getInt(48);
                int ncolors = mapped.getInt(76);
                int offset = headerSize + ncolors * 12;
                if (bitsPerPixel != 32 || bytesPerLine != width * 4
                        || offset + (long) bytesPerLine * height > channel.size()) {
                    return false;
                }
                mapped.position(offset);
                pixels = mapped.slice();
                fbWidth = width;
                fbHeight = height;
                quad = null;
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        /** Rectangle plein ecran, avec bandes noires si les formats d'image different. */
        private FloatBuffer buildQuad() {
            float sx = 1f;
            float sy = 1f;
            if (fbWidth > 0 && viewWidth > 0) {
                float fbAspect = (float) fbWidth / fbHeight;
                float viewAspect = (float) viewWidth / viewHeight;
                if (viewAspect > fbAspect) sx = fbAspect / viewAspect;
                else sy = viewAspect / fbAspect;
            }
            float[] data = {
                    -sx, -sy, 0f, 1f,
                    sx, -sy, 1f, 1f,
                    -sx, sy, 0f, 0f,
                    sx, sy, 1f, 0f,
            };
            FloatBuffer buffer = ByteBuffer.allocateDirect(data.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            buffer.put(data).position(0);
            return buffer;
        }

        private static int buildProgram() {
            int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
            int prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, vs);
            GLES20.glAttachShader(prog, fs);
            GLES20.glLinkProgram(prog);
            return prog;
        }

        private static int compile(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            return shader;
        }
    }
}
