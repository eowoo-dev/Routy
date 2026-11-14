// Deterministic straight-road low-speed bicycle model. Rear axle is internal; exported s/d is body center.
export class EnvironmentSimulation {
  constructor(runId = 'demo') {
    this.runId = runId; this.sequence = 0; this.atMs = 0;
    this.s = 0; this.d = 0; this.heading = 0;
  }
  step({ speed = 10, steeringDeg = 0, signal = 'OFF', light = 'RED', stopLineS = 150, vehicles = [] } = {}, dtMs = 50) {
    if (![speed, steeringDeg, dtMs, stopLineS].every(Number.isFinite) || speed < 0 || speed > 60 || dtMs <= 0 || dtMs > 250) throw new Error('Invalid simulation input');
    const dt = dtMs / 1000;
    this.s += speed * Math.cos(this.heading) * dt;
    this.d += speed * Math.sin(this.heading) * dt;
    this.heading += speed / 2.8 * Math.tan(Math.max(-35, Math.min(35, steeringDeg / 15)) * Math.PI / 180) * dt;
    this.atMs += dtMs;
    return { version: 2, source: 'SIMULATED_ENVIRONMENT', runId: this.runId, sequence: ++this.sequence,
      atMs: this.atMs, s: this.s + Math.cos(this.heading), d: this.d + Math.sin(this.heading),
      heading: this.heading, speed, signal, light, stopLineS, available: true,
      vehicles: vehicles.map(v => ({ length: 4.8, width: 1.8, ...v })) };
  }
}
