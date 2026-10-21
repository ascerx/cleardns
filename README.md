# ClearDNS

App Android tipo RethinkDNS-lite: DNS privado cifrado (DoH) con bloqueo de trackers, mediante un VPN local. Monetización: AdMob (banner + rewarded "quitar ads 24h").

## Arquitectura

- **VpnService local** (`DnsVpnService`): truco clásico de apps DNS sin root — solo la ruta del DNS falso (10.111.222.3) va al TUN; el resto del tráfico no se toca. Intercepta UDP/53, resuelve vía DoH y bloquea dominios (NXDOMAIN) si están en la blocklist.
- **`DohClient`**: DNS-over-HTTPS RFC 8484 (endpoint configurable: Google por defecto, Cloudflare disponible).
- **`BlocklistManager`**: blocklist formato hosts (seed en assets + descarga de `small.oisd.nl` al pulsar "Update blocklist").
- **`DnsProtocol`**: parser DNS mínimo + construcción de paquetes IPv4/UDP para responder por el TUN.
- **AdMob**: banner en pantalla principal + rewarded que quita ads 24h (double-tier: usuarios light ven ads, usuarios que odian ads pagan con atención).

## IDs de AdMob (PENDIENTE antes de publicar)

En `app/build.gradle.kts` hay placeholders de prueba de Google:
- `admob_app_id` (meta-data en Manifest) → reemplazar por el App ID real de tu consola AdMob
- `AD_UNIT_BANNER` y `AD_UNIT_REWARDED` (BuildConfig) → reemplazar por tus ad units reales

## Compilar

```bash
./gradlew assembleDebug    # APK debug
./gradlew bundleRelease    # AAB para Play Store
```

## Pendientes (v2)

- Firewall por app (estilo RethinkDNS completo) — requiere reenvío total de tráfico por TUN
- Selector de endpoint DoH en la UI
- Whitelist
- Estadísticas por dominio
