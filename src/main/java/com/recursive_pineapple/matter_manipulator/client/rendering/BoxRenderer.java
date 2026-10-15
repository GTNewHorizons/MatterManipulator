package com.recursive_pineapple.matter_manipulator.client.rendering;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;

import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.gtnhlib.client.renderer.CapturingTessellator;
import com.gtnewhorizon.gtnhlib.client.renderer.LocalTessellator;
import com.gtnewhorizon.gtnhlib.client.renderer.TessellatorManager;
import com.gtnewhorizon.gtnhlib.client.renderer.cel.model.quad.ModelQuadViewMutable;
import com.gtnewhorizon.gtnhlib.client.renderer.shader.ShaderProgram;
import com.gtnewhorizon.gtnhlib.client.renderer.vbo.VertexBuffer;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.DefaultVertexFormat;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods;

import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

public class BoxRenderer {

    public static final BoxRenderer INSTANCE = new BoxRenderer();

    private final ShaderProgram program;
    private final int time_location;

    private final VertexBuffer buffer = new VertexBuffer(DefaultVertexFormat.POSITION_TEXTURE_COLOR, GL11.GL_QUADS);

    public BoxRenderer() {
        program = new ShaderProgram(
            Mods.MatterManipulator.resourceDomain,
            "shaders/fancybox.vert.glsl",
            "shaders/fancybox.frag.glsl"
        );

        time_location = program.getUniformLocation("time");
    }

    private LocalTessellator tes;
    private List<ModelQuadViewMutable> collectedQuads;

    /**
     * Starts rendering fancy boxes. Should only be called once per frame, to allow quad sorting.
     */
    public void start(double partialTickTime) {
        tes = TessellatorManager.enterLocalMode();
        collectedQuads = new ArrayList<>();

        tes.startDrawing(GL11.GL_QUADS);

        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        double d0 = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTickTime;
        double d1 = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTickTime;
        double d2 = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTickTime;

        tes.setTranslation(-d0, -d1, -d2);
    }

    /**
     * Draws a fancy box around an AABB.
     */
    public void drawAround(AxisAlignedBB aabb, Vector3f colour) {
        drawAround(aabb, colour, 0);
    }

    /**
     * Draws a fancy box around an AABB, without some of its sides. This is used to draw several boxes that look like
     * one shape: the sides where two boxes touch are left out. The pattern is based on world coordinates (u + v is
     * x + y + z on every face), so it continues across the edges of a box and lines up between boxes.
     *
     * @param openSides The {@link ForgeDirection} flags of the sides to leave out. These sides aren't expanded either,
     *        so that the boxes touch exactly.
     */
    public void drawAround(AxisAlignedBB aabb, Vector3f colour, int openSides) {
        double x0 = aabb.minX - ((openSides & ForgeDirection.WEST.flag) == 0 ? 0.01 : 0);
        double y0 = aabb.minY - ((openSides & ForgeDirection.DOWN.flag) == 0 ? 0.01 : 0);
        double z0 = aabb.minZ - ((openSides & ForgeDirection.NORTH.flag) == 0 ? 0.01 : 0);
        double x1 = aabb.maxX + ((openSides & ForgeDirection.EAST.flag) == 0 ? 0.01 : 0);
        double y1 = aabb.maxY + ((openSides & ForgeDirection.UP.flag) == 0 ? 0.01 : 0);
        double z1 = aabb.maxZ + ((openSides & ForgeDirection.SOUTH.flag) == 0 ? 0.01 : 0);

        tes.setColorRGBA_F(colour.x, colour.y, colour.z, 0.25f);

        // spotless:off
        if ((openSides & ForgeDirection.DOWN.flag) == 0) {
            drawFace(x0, y0, z0, y0 + x0, z0,   x1, y0, z0, y0 + x1, z0,   x1, y0, z1, y0 + x1, z1,   x0, y0, z1, y0 + x0, z1);
        }

        if ((openSides & ForgeDirection.UP.flag) == 0) {
            drawFace(x0, y1, z0, y1 + x0, z0,   x0, y1, z1, y1 + x0, z1,   x1, y1, z1, y1 + x1, z1,   x1, y1, z0, y1 + x1, z0);
        }

        if ((openSides & ForgeDirection.WEST.flag) == 0) {
            drawFace(x0, y0, z0, x0 + y0, z0,   x0, y0, z1, x0 + y0, z1,   x0, y1, z1, x0 + y1, z1,   x0, y1, z0, x0 + y1, z0);
        }

        if ((openSides & ForgeDirection.EAST.flag) == 0) {
            drawFace(x1, y1, z1, x1 + y1, z1,   x1, y0, z1, x1 + y0, z1,   x1, y0, z0, x1 + y0, z0,   x1, y1, z0, x1 + y1, z0);
        }

        if ((openSides & ForgeDirection.NORTH.flag) == 0) {
            drawFace(x0, y0, z0, z0 + x0, y0,   x1, y0, z0, z0 + x1, y0,   x1, y1, z0, z0 + x1, y1,   x0, y1, z0, z0 + x0, y1);
        }

        if ((openSides & ForgeDirection.SOUTH.flag) == 0) {
            drawFace(x0, y0, z1, z1 + x0, y0,   x0, y1, z1, z1 + x0, y1,   x1, y1, z1, z1 + x1, y1,   x1, y0, z1, z1 + x1, y0);
        }
        // spotless:on

        tes.collectQuads(collectedQuads);
    }

    /**
     * Draws both sides of a quad. Each vertex is given as x, y, z, u, v.
     */
    private void drawFace(double... v) {
        for (int i = 0; i < 4; i++) {
            tes.addVertexWithUV(v[i * 5], v[i * 5 + 1], v[i * 5 + 2], v[i * 5 + 3], v[i * 5 + 4]);
        }

        for (int i = 3; i >= 0; i--) {
            tes.addVertexWithUV(v[i * 5], v[i * 5 + 1], v[i * 5 + 2], v[i * 5 + 3], v[i * 5 + 4]);
        }
    }

    /**
     * Actually draws the stored boxes.
     */
    public void finish() {
        tes.collectQuads(collectedQuads);
        TessellatorManager.exitLocalMode();

        collectedQuads.sort(new QuadViewComparator());

        ByteBuffer bytes = CapturingTessellator.quadsToBuffer(collectedQuads, buffer.getVertexFormat());

        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        program.use();

        GL20.glUniform1f(time_location, (((float) (System.currentTimeMillis() % 2500)) / 1000f));

        buffer.uploadStream(bytes);
        buffer.render();

        ShaderProgram.clear();

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);

        tes = null;
        collectedQuads = null;
    }
}
