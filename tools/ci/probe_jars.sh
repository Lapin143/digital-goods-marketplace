#!/usr/bin/env bash
# Разведка: сигнатуры классов библиотек, чтобы не гадать об API (CI-only, ветка probe/jars). Аргумент: номер части.
set -uo pipefail
[ -f /tmp/cp.txt ] || { ./gradlew -q --console=plain :services:api-gateway:printClasspath > /tmp/cp.txt 2>/tmp/cp.err || { echo "::error title=gradle::$(tail -5 /tmp/cp.err | tr '\n' ' ')"; exit 1; }; }
CP=$(grep '^TJAR ' /tmp/cp.txt | sed 's/^TJAR //' | tr '\n' ':')
notice() { local title="$1" body="$2"; body=${body//'%'/'%25'}; body=${body//$'\r'/}; body=${body//$'\n'/'%0A'}; echo "::notice title=$title::$body"; }
sig() { local out; out=$(javap -public -cp "$CP" "$@" 2>&1 | cut -c1-200 | head -n 60); notice "javap $1" "$out"; }
case "${1:-1}" in
  1) for c in org.springframework.cloud.gateway.support.AbstractStatefulConfigurable \
              org.springframework.cloud.gateway.filter.ratelimit.RateLimiter \
              org.springframework.boot.ssl.SslBundle \
              io.netty.handler.ssl.JdkSslContext \
              reactor.netty.http.client.HttpClient \
              'reactor.netty.tcp.SslProvider$SslContextSpec' \
              'reactor.netty.tcp.SslProvider$Builder' \
              org.springframework.cloud.gateway.support.ServerWebExchangeUtils; do sig "$c"; done ;;
  2) for j in $(grep '^TJAR ' /tmp/cp.txt | sed 's/^TJAR //' | grep -E 'spring-cloud-gateway-server-webflux|spring-boot-data-redis|spring-boot-webflux|spring-boot-reactor-netty'); do
       names=$(unzip -Z1 "$j" | grep -E 'AutoConfiguration(\.imports|\.class)$|HttpClientCustomizer|Customizer\.class' | head -n 40)
       notice "jar $(basename "$j")" "$names"
     done ;;
  3) for j in $(grep '^TJAR ' /tmp/cp.txt | sed 's/^TJAR //' | grep -E 'spring-cloud-gateway-server-webflux|spring-boot-data-redis|spring-boot-webflux|spring-boot-reactor-netty|spring-boot-actuator-autoconfigure|spring-boot-autoconfigure'); do
       meta=$(unzip -p "$j" META-INF/spring-configuration-metadata.json 2>/dev/null | grep -o '"name": *"[^"]*"' | grep -E 'httpclient|x-forwarded|redis\.(ssl|username|password|host|port|lettuce)|server\.ssl\.bundle|ratelimit|gateway\.server\.webflux\.(metrics|route-defin|default-filters|globalcors)' | head -n 70 | tr '\n' ' ')
       [ -n "$meta" ] && notice "props $(basename "$j")" "$meta"
     done ;;
esac
exit 0
