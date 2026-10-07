import { drawDiscNodeHover, drawDiscNodeLabel, NodeProgram, type ProgramInfo } from "sigma/rendering";
import type { NodeDisplayData, RenderParams } from "sigma/types";
import { floatColor } from "sigma/utils";

const FRAGMENT_SHADER_SOURCE = `
precision mediump float;
varying vec4 v_color;
void main(void) {
  gl_FragColor = v_color;
}
`;

const VERTEX_SHADER_SOURCE = `
attribute vec4 a_id;
attribute vec4 a_color;
attribute vec2 a_position;
attribute float a_size;
attribute float a_angle;

uniform mat3 u_matrix;
uniform float u_sizeRatio;
uniform float u_cameraAngle;
uniform float u_correctionRatio;

varying vec4 v_color;
const float bias = 255.0 / 254.0;

void main() {
  float size = a_size * u_correctionRatio / u_sizeRatio;
  float angle = a_angle + u_cameraAngle;
  vec2 offset = size * vec2(cos(angle), sin(angle));
  gl_Position = vec4((u_matrix * vec3(a_position + offset, 1)).xy, 0, 1);
  #ifdef PICKING_MODE
  v_color = a_id;
  #else
  v_color = a_color;
  #endif
  v_color.a *= bias;
}
`;

const { UNSIGNED_BYTE, FLOAT } = WebGLRenderingContext;
const UNIFORMS = ["u_sizeRatio", "u_correctionRatio", "u_cameraAngle", "u_matrix"] as const;
const TRIANGLE_ANGLES = [0, (2 * Math.PI) / 3, (-2 * Math.PI) / 3];

export class NodeTriangleProgram extends NodeProgram<(typeof UNIFORMS)[number]> {
  drawHover = drawDiscNodeHover;
  drawLabel = drawDiscNodeLabel;

  getDefinition() {
    return {
      VERTICES: 3,
      VERTEX_SHADER_SOURCE,
      FRAGMENT_SHADER_SOURCE,
      METHOD: WebGLRenderingContext.TRIANGLES,
      UNIFORMS,
      ATTRIBUTES: [
        { name: "a_position", size: 2, type: FLOAT },
        { name: "a_size", size: 1, type: FLOAT },
        { name: "a_color", size: 4, type: UNSIGNED_BYTE, normalized: true },
        { name: "a_id", size: 4, type: UNSIGNED_BYTE, normalized: true },
      ],
      CONSTANT_ATTRIBUTES: [{ name: "a_angle", size: 1, type: FLOAT }],
      // Sigma expects one vector per vertex, even for a single-value attribute.
      // This program is initialized even when there are no sensor nodes.
      CONSTANT_DATA: TRIANGLE_ANGLES.map((angle) => [angle]),
    };
  }

  processVisibleItem(nodeIndex: number, startIndex: number, data: NodeDisplayData): void {
    const array = this.array;
    array[startIndex++] = data.x;
    array[startIndex++] = data.y;
    array[startIndex++] = data.size;
    array[startIndex++] = floatColor(data.color);
    array[startIndex] = nodeIndex;
  }

  setUniforms(params: RenderParams, { gl, uniformLocations }: ProgramInfo): void {
    const { u_sizeRatio, u_correctionRatio, u_cameraAngle, u_matrix } = uniformLocations;
    gl.uniform1f(u_sizeRatio, params.sizeRatio);
    gl.uniform1f(u_cameraAngle, params.cameraAngle);
    gl.uniform1f(u_correctionRatio, params.correctionRatio);
    gl.uniformMatrix3fv(u_matrix, false, params.matrix);
  }
}
