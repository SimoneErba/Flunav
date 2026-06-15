import { drawDiscNodeHover, drawDiscNodeLabel, NodeProgram } from "sigma/rendering";
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
attribute vec4 a_border_color;
attribute vec2 a_position;
attribute float a_size;
attribute float a_border_size;
attribute float a_angle;
attribute float a_layer;

uniform mat3 u_matrix;
uniform float u_sizeRatio;
uniform float u_cameraAngle;
uniform float u_correctionRatio;

varying vec4 v_color;

const float bias = 255.0 / 254.0;
const float sqrt_8 = sqrt(8.0);

void main() {
  float nodeSize = a_size + ((1.0 - a_layer) * a_border_size);
  float size = nodeSize * u_correctionRatio / u_sizeRatio * sqrt_8;
  float angle = a_angle + u_cameraAngle;
  vec2 diffVector = size * vec2(cos(angle), sin(angle));
  vec2 position = a_position + diffVector;

  gl_Position = vec4(
    (u_matrix * vec3(position, 1)).xy,
    0,
    1
  );

  #ifdef PICKING_MODE
  v_color = a_id;
  #else
  v_color = mix(a_border_color, a_color, a_layer);
  #endif

  v_color.a *= bias;
}
`;

const { UNSIGNED_BYTE, FLOAT } = WebGLRenderingContext;
const UNIFORMS = ["u_sizeRatio", "u_correctionRatio", "u_cameraAngle", "u_matrix"] as const;
const PI = Math.PI;
const OUTER_LAYER = 0;
const INNER_LAYER = 1;

const squareAngles = [PI / 4, 3 * PI / 4, -PI / 4, 3 * PI / 4, -PI / 4, -3 * PI / 4];
const CONSTANT_DATA = [
  ...squareAngles.map((angle) => [angle, OUTER_LAYER]),
  ...squareAngles.map((angle) => [angle, INNER_LAYER]),
];

type PriorityNodeDisplayData = NodeDisplayData & {
  borderColor?: string;
  borderSize?: number;
};

export class NodeBorderedSquareProgram extends NodeProgram<(typeof UNIFORMS)[number]> {
  drawHover = drawDiscNodeHover;
  drawLabel = drawDiscNodeLabel;

  getDefinition() {
    return {
      VERTICES: 12,
      VERTEX_SHADER_SOURCE,
      FRAGMENT_SHADER_SOURCE,
      METHOD: WebGLRenderingContext.TRIANGLES,
      UNIFORMS,
      ATTRIBUTES: [
        { name: "a_position", size: 2, type: FLOAT },
        { name: "a_size", size: 1, type: FLOAT },
        { name: "a_color", size: 4, type: UNSIGNED_BYTE, normalized: true },
        { name: "a_border_color", size: 4, type: UNSIGNED_BYTE, normalized: true },
        { name: "a_border_size", size: 1, type: FLOAT },
        { name: "a_id", size: 4, type: UNSIGNED_BYTE, normalized: true },
      ],
      CONSTANT_ATTRIBUTES: [
        { name: "a_angle", size: 1, type: FLOAT },
        { name: "a_layer", size: 1, type: FLOAT },
      ],
      CONSTANT_DATA,
    };
  }

  processVisibleItem(nodeIndex: number, startIndex: number, data: NodeDisplayData): void {
    const array = this.array;
    const priorityData = data as PriorityNodeDisplayData;
    const borderSize = typeof priorityData.borderSize === "number" ? priorityData.borderSize : 0;
    const borderColor = typeof priorityData.borderColor === "string" ? priorityData.borderColor : data.color;

    array[startIndex++] = data.x;
    array[startIndex++] = data.y;
    array[startIndex++] = data.size;
    array[startIndex++] = floatColor(data.color);
    array[startIndex++] = floatColor(borderColor);
    array[startIndex++] = borderSize;
    array[startIndex++] = nodeIndex;
  }

  setUniforms(params: RenderParams, { gl, uniformLocations }: any): void {
    const { u_sizeRatio, u_correctionRatio, u_cameraAngle, u_matrix } = uniformLocations;
    gl.uniform1f(u_sizeRatio, params.sizeRatio);
    gl.uniform1f(u_cameraAngle, params.cameraAngle);
    gl.uniform1f(u_correctionRatio, params.correctionRatio);
    gl.uniformMatrix3fv(u_matrix, false, params.matrix);
  }
}
