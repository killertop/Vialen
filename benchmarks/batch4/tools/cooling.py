"""Bounded passive cooling between workloads; never modifies device settings."""
import json,time

class CoolingBudget:
 def __init__(self,device,max_wait=300,total_wait=1800,clock=time.monotonic,sleep=time.sleep):
  self.device=device;self.max_wait=max_wait;self.remaining=total_wait;self.clock=clock;self.sleep=sleep
 def wait(self,name):
  start=self.clock();checks=[];ready_count=0;power=None
  try:
   while True:
    env=self.device.environment();checks.append(env)
    current_power=tuple(env.get(k) for k in ['ac','usb','status'])
    if None in current_power:raise RuntimeError('Cooling power state missing')
    if power is None:power=current_power
    if current_power!=power:raise RuntimeError('Power changed during cooling')
    try:temperature=int(env['temperature_tenths_c']);thermal=int(env['thermal_status'])
    except (KeyError,TypeError,ValueError):raise RuntimeError('Cooling temperature/thermal state missing') from None
    ready_count=ready_count+1 if temperature<=370 and thermal==0 else 0
    if ready_count>=2:return
    elapsed=self.clock()-start
    delay=2 if ready_count else 30
    if elapsed+delay>min(self.max_wait,self.remaining):raise RuntimeError('Passive cooling budget exhausted')
    print(json.dumps({'cooling':name,'temperature_tenths_c':temperature,'thermal_status':thermal}),flush=True)
    self.sleep(delay)
  finally:
   elapsed=self.clock()-start;self.remaining=max(0,self.remaining-elapsed)
   (self.device.root/(name+'-cooling.json')).write_text(json.dumps({'elapsed_s':elapsed,'remaining_s':self.remaining,'checks':checks},indent=2))
