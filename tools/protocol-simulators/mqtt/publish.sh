#!/bin/sh
set -eu
while :; do
  mosquitto_pub -h mqtt -p 1883 -V mqttv311 -r -t protoforge/device001/hvac/current_temp -m '{"value":25.6}'
  mosquitto_pub -h mqtt -p 1883 -V mqttv311 -r -t protoforge/device001/hvac/set_temp -m '{"value":24.0}'
  mosquitto_pub -h mqtt -p 1883 -V mqttv311 -r -t protoforge/device001/hvac/mode -m '{"value":1}'
  mosquitto_pub -h mqtt -p 1883 -V mqttv311 -r -t protoforge/device001/hvac/fan -m '{"value":2}'
  mosquitto_pub -h mqtt -p 1883 -V mqttv311 -r -t protoforge/device001/hvac/power -m '{"value":true}'
  mosquitto_pub -h mqtt -p 1883 -V mqttv311 -r -t protoforge/device001/hvac/humidity -m '{"value":58.2}'
  sleep 5
done
