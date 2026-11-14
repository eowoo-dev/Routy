// Applied in memory only after the upstream SHA-256 has been verified.
export function patchHighway(source) {
  const anchor = '    renderer.render(scene, camera);';
  if (source.split(anchor).length !== 2) throw new Error('Renderer patch anchor is ambiguous or missing');
  return "import { publishEnvironment } from '/routy/environment-publisher.mjs';\n" + source.replace(anchor, `
    publishEnvironment({
      ego: { lateralPositionM: ego.group.position.x, visualYawRad: ego.body.rotation.y - Math.PI },
      lanes: { widthM: 3.7, centersM: [-3.7, 0, 3.7], boundariesM: [-5.55, -1.85, 1.85, 5.55] },
      traffic: traffic.map((car, index) => ({ id: 'visual-' + index, lateralPositionM: car.x, longitudinalPositionM: car.z })),
      distanceM: distance,
      trafficSignals: null,
      stopLines: null,
    });
${anchor}`);
}
