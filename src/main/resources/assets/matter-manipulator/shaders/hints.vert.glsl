#version 330 compatibility

// faces are in IIcon side order: down, up, north, south, west, east.
const vec3 CORNERS[24] = vec3[](
    vec3(0, 0, 0), vec3(1, 0, 0), vec3(1, 0, 1), vec3(0, 0, 1),
    vec3(0, 1, 0), vec3(0, 1, 1), vec3(1, 1, 1), vec3(1, 1, 0),
    vec3(0, 0, 0), vec3(0, 1, 0), vec3(1, 1, 0), vec3(1, 0, 0),
    vec3(1, 0, 1), vec3(1, 1, 1), vec3(0, 1, 1), vec3(0, 0, 1),
    vec3(0, 0, 1), vec3(0, 1, 1), vec3(0, 1, 0), vec3(0, 0, 0),
    vec3(1, 0, 0), vec3(1, 1, 0), vec3(1, 1, 1), vec3(1, 0, 1)
);

// 0 = the icon's min u/v, 1 = its max u/v
const vec2 UVS[24] = vec2[](
    vec2(0, 0), vec2(1, 0), vec2(1, 1), vec2(0, 1),
    vec2(0, 0), vec2(0, 1), vec2(1, 1), vec2(1, 0),
    vec2(1, 1), vec2(1, 0), vec2(0, 0), vec2(0, 1),
    vec2(1, 1), vec2(1, 0), vec2(0, 0), vec2(0, 1),
    vec2(1, 1), vec2(1, 0), vec2(0, 0), vec2(0, 1),
    vec2(1, 1), vec2(1, 0), vec2(0, 0), vec2(0, 1)
);

// two triangles per face
const int QUAD[6] = int[](0, 1, 2, 0, 2, 3);

layout(location = 0) in float a_Vertex; // 0-35
layout(location = 1) in vec3 a_Pos; // the block, relative to the translated origin
layout(location = 2) in int a_Material;
layout(location = 3) in vec4 a_Tint;

// The atlas rect (min u, min v, max u, max v) of face x of material y
uniform sampler2D u_Materials;

out vec2 v_UV;
out vec4 v_Tint;

void main() {
    int vertex = int(a_Vertex);
    int face = vertex / 6;
    int corner = face * 4 + QUAD[vertex % 6];

    vec4 rect = texelFetch(u_Materials, ivec2(face, a_Material), 0);

    v_UV = mix(rect.xy, rect.zw, UVS[corner]);
    v_Tint = a_Tint;

    gl_Position = rect == vec4(0.0) ? vec4(0.0) : gl_ModelViewProjectionMatrix * vec4(a_Pos + 0.25 + CORNERS[corner] * 0.5, 1.0);
}
