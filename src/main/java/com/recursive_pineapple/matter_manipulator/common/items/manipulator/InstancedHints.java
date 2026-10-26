package com.recursive_pineapple.matter_manipulator.common.items.manipulator;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memByteBuffer;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import com.gtnewhorizon.gtnhlib.client.renderer.shader.ShaderProgram;
import com.recursive_pineapple.matter_manipulator.MMMod;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLContext;

/// Draws the hints with GPU instancing: each hint is [#STRIDE] bytes instead of 24 vertices, and the vertex shader
/// builds the cube. The atlas rects of the 6 faces of each material are in a small float texture. NEEDS OpenGL 3.3!! If
/// not, [RenderHints] uses the old renderer when [#isSupported()] is false.
class InstancedHints {

    /// Bytes per hint: position (3 floats), material (int), tint (4 unsigned bytes)
    static final int STRIDE = 20;

    /// 0 is the block atlas, 1 the light map
    private static final int MATERIAL_UNIT = 2;

    private static Boolean supported;
    private static ShaderProgram program;
    private static int vao, instanceBuffer, materialTexture;
    private static int count;

    static boolean isSupported() {
        if (supported == null) {
            try {
                supported = init();
            } catch (Throwable t) {
                MMMod.LOG.error("Could not set up instanced hints, using the old renderer", t);
                supported = false;
            }
        }

        return supported;
    }

    private static boolean init() {
        if (!GLContext.getCapabilities().OpenGL33) return false;

        program = new ShaderProgram(Mods.MatterManipulator.resourceDomain, "shaders/hints.vert.glsl", "shaders/hints.frag.glsl");

        if (program.getProgram() == 0) return false;

        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        program.use();
        GL20.glUniform1i(program.getUniformLocation("u_Materials"), MATERIAL_UNIT);
        GL20.glUseProgram(prevProgram);

        ByteBuffer mesh = BufferUtils.createByteBuffer(36);
        for (int i = 0; i < 36; i++)
            mesh.put((byte) i);
        mesh.flip();

        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, GL15.glGenBuffers());
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, mesh, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 1, GL11.GL_UNSIGNED_BYTE, false, 1, 0);
        GL20.glEnableVertexAttribArray(0);

        instanceBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, STRIDE, 0);
        GL30.glVertexAttribIPointer(2, 1, GL11.GL_INT, STRIDE, 12);
        GL20.glVertexAttribPointer(3, 4, GL11.GL_UNSIGNED_BYTE, true, STRIDE, 16);

        for (int i = 1; i <= 3; i++) {
            GL20.glEnableVertexAttribArray(i);
            GL33.glVertexAttribDivisor(i, 1);
        }

        GL30.glBindVertexArray(prevVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        materialTexture = GL11.glGenTextures();
        onMaterialUnit(() -> {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        });

        MMMod.LOG.info("Hints will use instanced rendering");

        return true;
    }

    /// @param materials 6 atlas rects (min u, min v, max u, max v) per material; all zeroes for a face without an icon
    static void upload(long data, int bytes, FloatBuffer materials) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, memByteBuffer(data, bytes), GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        onMaterialUnit(
            () -> GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 6, materials.remaining() / 24, 0, GL11.GL_RGBA, GL11.GL_FLOAT, materials)
        );

        count = bytes / STRIDE;
    }

    static int count() {
        return count;
    }

    static void clear() {
        count = 0;
    }

    static void draw() {
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        program.use();

        onMaterialUnit(() -> {
            GL30.glBindVertexArray(vao);
            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 36, count);
            GL30.glBindVertexArray(prevVao);
        });

        GL20.glUseProgram(prevProgram);
    }

    private static void onMaterialUnit(Runnable action) {
        int prevUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + MATERIAL_UNIT);
        int prevTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, materialTexture);

        action.run();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture);
        GL13.glActiveTexture(prevUnit);
    }
}
