package dev.gltfexport.export;

import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * A {@link VertexConsumer} that records what vanilla code writes into it instead of uploading it to the GPU.
 * Everything Minecraft draws through these paths is quads, so vertices are read back four at a time.
 */
public final class CaptureConsumer implements VertexConsumer {
	public final FloatArrayList pos = new FloatArrayList();
	public final FloatArrayList uv = new FloatArrayList();
	public final IntArrayList color = new IntArrayList();
	private int count;

	public void clear() {
		pos.clear();
		uv.clear();
		color.clear();
		count = 0;
	}

	public int vertexCount() {
		return count;
	}

	public int quadCount() {
		return count / 4;
	}

	public float x(int v) {
		return pos.getFloat(v * 3);
	}

	public float y(int v) {
		return pos.getFloat(v * 3 + 1);
	}

	public float z(int v) {
		return pos.getFloat(v * 3 + 2);
	}

	public float u(int v) {
		return uv.getFloat(v * 2);
	}

	public float v(int v) {
		return uv.getFloat(v * 2 + 1);
	}

	public int argb(int v) {
		return color.getInt(v);
	}

	@Override
	public VertexConsumer addVertex(float x, float y, float z) {
		pos.add(x);
		pos.add(y);
		pos.add(z);
		uv.add(0f);
		uv.add(0f);
		color.add(-1);
		count++;
		return this;
	}

	@Override
	public VertexConsumer setColor(int r, int g, int b, int a) {
		if (count > 0) color.set(count - 1, (a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | b & 255);
		return this;
	}

	@Override
	public VertexConsumer setColor(int argb) {
		if (count > 0) color.set(count - 1, argb);
		return this;
	}

	@Override
	public VertexConsumer setUv(float u, float v) {
		if (count > 0) {
			uv.set((count - 1) * 2, u);
			uv.set((count - 1) * 2 + 1, v);
		}
		return this;
	}

	@Override
	public VertexConsumer setUv1(int u, int v) {
		return this;
	}

	@Override
	public VertexConsumer setUv2(int u, int v) {
		return this;
	}

	@Override
	public VertexConsumer setUv3(float u, float v) {
		return this;
	}

	@Override
	public VertexConsumer setNormal(float x, float y, float z) {
		return this;
	}

	@Override
	public VertexConsumer setLineWidth(float width) {
		return this;
	}
}
