#!/usr/bin/env bash
# Разведка: сигнатуры классов библиотек, чтобы не гадать об API (CI-only, ветка probe/jars)
set -uo pipefail
./gradlew -q --console=plain :services:api-gateway:printClasspath > /tmp/cp.txt 2>/tmp/cp.err || { echo "::error title=gradle::$(tail -5 /tmp/cp.err | tr '\n' ' ')"; exit 1; }
CP=$(grep '^TJAR ' /tmp/cp.txt | sed 's/^TJAR //' | tr '\n' ':')
notice() { local title="$1" body="$2"; body=${body//'%'/'%25'}; body=${body//$'\r'/}; body=${body//$'\n'/'%0A'}; echo "::notice title=$title::$body"; }
sig() { local out; out=$(javap -public -cp "$CP" "$@" 2>&1 | cut -c1-230 | head -n 70); notice "javap $1" "$out"; }
for c in org.springframework.cloud.gateway.config.HttpClientCustomizer \
         org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter \
         'org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter$Config' \
         org.springframework.cloud.gateway.filter.ratelimit.AbstractRateLimiter \
         'org.springframework.cloud.gateway.filter.ratelimit.RateLimiter$Response' \
         org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder \
         org.springframework.cloud.gateway.route.builder.PredicateSpec \
         org.springframework.cloud.gateway.route.builder.BooleanSpec \
         org.springframework.cloud.gateway.route.builder.UriSpec \
         org.springframework.boot.ssl.SslBundles \
         org.springframework.boot.ssl.SslBundle \
         io.netty.handler.ssl.JdkSslContext \
         reactor.netty.http.client.HttpClient \
         'reactor.netty.tcp.SslProvider$SslContextSpec' \
         'reactor.netty.tcp.SslProvider$Builder' \
         org.springframework.cloud.gateway.support.ServerWebExchangeUtils ; do
  sig "$c"
done
# имена классов автонастройки шлюза и Redis, ключи настроек
for j in $(grep '^TJAR ' /tmp/cp.txt | sed 's/^TJAR //' | grep -E 'spring-cloud-gateway-server-webflux|spring-boot-data-redis|spring-boot-webflux|spring-boot-reactor-netty|spring-cloud-gateway'); do
  names=$(unzip -Z1 "$j" | grep -E 'AutoConfiguration(\.imports|\.class)$|HttpClient|Customizer' | head -n 40)
  notice "jar $(basename "$j")" "$names"
  meta=$(unzip -p "$j" META-INF/spring-configuration-metadata.json 2>/dev/null | grep -o '"name": *"[^"]*"' | grep -E 'httpclient|x-forwarded|redis\.(ssl|username|password|host|port|lettuce)|ssl\.bundle|metrics' | head -n 60 | tr '\n' ' ')
  [ -n "$meta" ] && notice "props $(basename "$j")" "$meta"
done
exit 0
