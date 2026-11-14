// Applied in memory only after the upstream SHA-256 has been verified.
export function patchHighway(source) {
  const anchor = '    renderer.render(scene, camera);';
  if (source.split(anchor).length !== 2) throw new Error('Renderer patch anchor is ambiguous or missing');
  source=source.replace('    const steering = clamp(s.steering_deg / 540, -1, 1);', '    const environment = readEnvironment();\n    const steering = clamp(s.steering_deg / 540, -1, 1);');
  // The camera looks along +Z: world +X is screen-left, matching model positive-left d.
  source=source.replace('ego.group.position.x = THREE.MathUtils.damp(ego.group.position.x, -steering * 1.15, 5, dt);', 'ego.group.position.x = environment ? environment.d : THREE.MathUtils.damp(ego.group.position.x, -steering * 1.15, 5, dt);');
  source=source.replace('ego.body.rotation.y = Math.PI - steering * .13;', 'ego.body.rotation.y = environment ? Math.PI + environment.heading : Math.PI - steering * .13;');
  source=source.replace('camera.lookAt(driverX - steering * 1.4, 1.05, 65);', 'camera.lookAt(driverX + (environment ? Math.sin(environment.heading) * 65 : -steering * 1.4), 1.05, environment ? .35 + Math.cos(environment.heading) * 65 : 65);');
  source=source.replace('  let weatherTime = 0;', `
  const routyStopLine=new THREE.Mesh(new THREE.PlaneGeometry(11.1,.3),new THREE.MeshBasicMaterial({color:'#ffffff'}));
  routyStopLine.rotation.x=-Math.PI/2; routyStopLine.position.y=.04; scene.add(routyStopLine);
  const routyLight=new THREE.Mesh(new THREE.SphereGeometry(.45),new THREE.MeshBasicMaterial({color:'#ff3333'}));
  scene.add(routyLight);
  let weatherTime = 0;`);
  source=source.replace('    for (const car of traffic) car.group.position.set(car.x, 0, car.z);', `
    for (const [index,car] of traffic.entries()) {
      const object=environment?.vehicles[index];
      car.group.visible = !environment || Boolean(object);
      if(object) { car.x=object.d; car.z=object.s-environment.s; }
      car.group.position.set(car.x,0,car.z);
    }
    if(environment) roadMaterial.uniforms.distance.value=environment.s%12000;
    routyStopLine.visible=Boolean(environment);
    routyLight.visible=Boolean(environment);
    if(environment) {
      routyStopLine.position.z=environment.stopLineS-environment.s;
      routyLight.position.set(6,4,environment.stopLineS-environment.s);
      routyLight.material.color.set(environment.light==='RED'?'#ff3333':environment.light==='GREEN'?'#33ff66':'#ffcc33');
    }`);
  return "import { publishEnvironment, readEnvironment } from '/routy/environment-publisher.mjs';\n" + source.replace(anchor, `
    publishEnvironment({
      input: { speed: s.gear === 'Drive' ? s.speed_kph / 3.6 : 0, steeringDeg: -s.steering_deg, signal: 'UNKNOWN' },
      ego: { lateralPositionM: ego.group.position.x, visualYawRad: ego.body.rotation.y - Math.PI },
      lanes: { widthM: 3.7, centersM: [-3.7, 0, 3.7], boundariesM: [-5.55, -1.85, 1.85, 5.55] },
      traffic: traffic.map((car, index) => ({ id: 'visual-' + index, lateralPositionM: car.x, longitudinalPositionM: car.z })),
      distanceM: distance,
      trafficSignals: null,
      stopLines: null,
    });
${anchor}`);
}
