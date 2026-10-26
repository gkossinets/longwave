#!/bin/bash
# usage: soap.sh IP SERVICE ACTION ARGS_XML
ip=$1; svc=$2; act=$3; args=$4
case $svc in
  AVTransport) path=/MediaRenderer/AVTransport/Control; ns=urn:schemas-upnp-org:service:AVTransport:1;;
  RenderingControl) path=/MediaRenderer/RenderingControl/Control; ns=urn:schemas-upnp-org:service:RenderingControl:1;;
  AlarmClock) path=/AlarmClock/Control; ns=urn:schemas-upnp-org:service:AlarmClock:1;;
  ZoneGroupTopology) path=/ZoneGroupTopology/Control; ns=urn:schemas-upnp-org:service:ZoneGroupTopology:1;;
esac
curl -s -m 8 http://$ip:1400$path -H 'Content-Type: text/xml; charset=utf-8' -H "SOAPACTION: $ns#$act" \
  --data "<?xml version='1.0'?><s:Envelope xmlns:s='http://schemas.xmlsoap.org/soap/envelope/' s:encodingStyle='http://schemas.xmlsoap.org/soap/encoding/'><s:Body><u:$act xmlns:u='$ns'>$args</u:$act></s:Body></s:Envelope>"
