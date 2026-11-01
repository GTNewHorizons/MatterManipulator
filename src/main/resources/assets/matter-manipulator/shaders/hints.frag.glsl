#version 330 compatibility

uniform sampler2D u_Texture;

in vec2 v_UV;
in vec4 v_Tint;

out vec4 fragColour;

void main() {
    fragColour = texture(u_Texture, v_UV) * v_Tint;
}
