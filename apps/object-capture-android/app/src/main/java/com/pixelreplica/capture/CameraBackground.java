package com.pixelreplica.capture;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Small external-texture renderer. Display transforms never alter exported image pixels. */
final class CameraBackground {
    private final FloatBuffer vertices = buffer(new float[]{-1,-1, 1,-1, -1,1, 1,1});
    private final FloatBuffer uv = buffer(new float[8]);
    int texture;
    private int program;
    private static FloatBuffer buffer(float[] values) {
        FloatBuffer b = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(values).position(0); return b;
    }
    void create() {
        int[] id = new int[1]; GLES20.glGenTextures(1, id, 0); texture = id[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        int vs = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 p;attribute vec2 t;varying vec2 v;void main(){gl_Position=vec4(p,0.,1.);v=t;}");
        int fs = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float;uniform samplerExternalOES s;varying vec2 v;void main(){gl_FragColor=texture2D(s,v);}");
        program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs); GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs);
    }
    private int shader(int kind, String source) {
        int shader = GLES20.glCreateShader(kind); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
        int[] status = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader)); return shader;
    }
    void draw(Frame frame) {
        if (frame.getTimestamp() == 0) return;
        vertices.position(0); uv.position(0);
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, vertices, Coordinates2d.TEXTURE_NORMALIZED, uv);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        int p = GLES20.glGetAttribLocation(program, "p"), t = GLES20.glGetAttribLocation(program, "t");
        vertices.position(0); uv.position(0);
        GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 0, vertices);
        GLES20.glVertexAttribPointer(t, 2, GLES20.GL_FLOAT, false, 0, uv);
        GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(t);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "s"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(t);
    }
}
