# Síntesis · ¿Existe una skill o un repo que haga el análisis de @ayoships? · 2026-11-01

**Videos:** Block Blast (ZSgT4rsbp), Subway Surfers (ZSgT4XxUW) y Candy Crush (ZSgT4SFY1), de @ayoships (Ayo Omolewa). Las fichas están en `90_VIDEOS_PROCESADOS/fichas/2026-11-01_*`.

**Tipo de análisis:**
1. Desarmar un juego móvil: descompilar el cliente y leer la configuración que baja del servidor.
2. Explicar su monetización:
   - perfil del jugador;
   - modelo de valor (XGBoost);
   - dificultad dinámica;
   - reglas de anuncios y ofertas;
   - A/B tests.

**Investigación:** 6 agentes. Los informes completos están en esta carpeta:
- `A1_android.md`
- `A2_nativo_js.md`
- `B1_dark_patterns_privacidad.md`
- `B2_monetizacion_juegos.md`
- `C1_skills_mcp.md`
- `C2_ayoships_y_similares.md`

## Respuesta corta
**No existe una skill "llave en mano" que haga el análisis completo de monetización.** Ningún registro revisado la tiene:
- anthropics/skills
- claude-plugins-official
- openai/skills
- el registro oficial de MCP
- las listas awesome-*

**Sí existe un stack gratis y combinable.** Las herramientas exponen el código y el comportamiento de la app al agente, y **el agente (Claude/Codex) hace la interpretación**. Así trabaja también ayoships: según su blog, usó "GPT-6 Astra" para portar y verificar el modelo XGBoost **[V, C2]**.

## Stack recomendado (todo gratis)
| Paso | Herramienta | Qué aporta | Evidencia |
|---|---|---|---|
| Código Java/Kotlin del APK | **SimoneAvogadro/android-reverse-engineering-skill**: plugin de Claude Code. Se instala con `/plugin marketplace add SimoneAvogadro/android-reverse-engineering-skill` y luego `/plugin install android-reverse-engineering@android-reverse-engineering-skill` | Descompila con jadx y Vineflower, y extrae APIs y URLs. 8,3k★, Apache-2.0. En Windows, scripts PowerShell "experimentales". La rama `trackers-and-ads` agrega `/find-ads` (SDKs de anuncios), pero no está en master | [V] (Exa + A1 + C1) |
| Explorar el APK en vivo | **zinja-coder/jadx-ai-mcp** + jadx-mcp-server | jadx-gui vía MCP: referencias cruzadas, strings, manifest. 2,9k★, Apache-2.0 | [V] |
| Librerías nativas (.so de Cocos o C++) | **LaurieWired/GhidraMCP**: `claude mcp add ghidra -- uvx --from git+https://github.com/LaurieWired/GhidraMCP ghidra-mcp --ghidra-server http://127.0.0.1:8080/` | Ghidra vía MCP. ~11,7k★. **Necesita exactamente Ghidra 12.1.4 (JDK 21)** | [V] (A2, C1) |
| JavaScript minificado (reglas y "traits") | **pionxzh/wakaru** (`npx wakaru bundle.js --unpack -o out/`; skill: `npx skills add pionxzh/wakaru`) + webcrack + humanify | Desempaqueta y legibiliza JS, incluidos bundles de Cocos Creator 2.x. 1,1k★ | [V] repo; el comando de la skill solo lo verificó A2 |
| Juegos Unity (como Subway Surfers) | Cpp2IL / la skill `reversing-unity-il2cpp` (trilwu/secskills, solo `secskills-core`) | Metadatos de IL2CPP | [V] (C1) |
| Correr la app y ver logs | **mobile-next/mobile-mcp**: `claude mcp add mobile-mcp -- npx -y @mobilenext/mobile-mcp@latest` | Emulador Android en Windows: logs, instalar, limpiar datos. 10,2k★ | [V] (A1, C1) |
| Todo en uno (inmaduro) | EncidXVII/claude-reverse-engineering-skill | Playbooks para APK, nativo, JS y Unity; CI en Windows; Claude Code y Codex. **Solo 5★ según Exa (22 según A2), creado el 2026-10-12** | [V] |

## Ruta sin descompilar (legal y fácil, sirve desde ya)
- **Exodus Privacy** (reports.exodus-privacy.eu.org): trackers y permisos por nombre de paquete. Por ejemplo, Subway Surfers tiene 23 trackers y 18 permisos **[V, B1]**.
- **PlayStore Radar** (playstoreradar.com/sdks): qué SDKs de anuncios y analítica usa cada uno de ~41.700 juegos **[V, B1]**.
- **PCAPdroid**: captura el tráfico por app sin root. Así se ven configuraciones remotas en JSON como la de Subway Surfers. **No descifrar apps con certificate pinning: eso es eludir una protección.**
- **DarkPattern.games**: patrones oscuros calificados por la comunidad, por juego.
- **Deconstructor of Fun**: teardowns escritos de juegos, gratis.

## Para tus juegos de Roblox (lo más aplicable)
- **Roblox Configs + Experiments** (Open Cloud): configuración remota y A/B tests gratis, el mismo patrón que Subway Surfers. Roblox recomienda tener ~500 DAU antes de experimentar **[V, B2]**.
- **Roblox Analytics:** eventos de economía y embudo, y Analytics Query API **[V]**.
- **Monetization Foundations playbook** + la política **Paid Random Items:** obliga a mostrar las probabilidades antes de comprar y a chequear PolicyService. Es la línea ética **[V]**.
- **Lección de diseño:**
  - Bien: dificultad adaptativa por progreso esperado (las bandas lower/upper de Candy Crush) y configuración remota con experimentos.
  - Mal: condicionar la dificultad al valor económico del jugador. Es riesgoso con la audiencia joven de Roblox.

## Alertas
- **Legal:** los ToS de King (5.5) y de SYBO prohíben descompilar; King también prohíbe romper cifrado **[S, C2]**. Ayoships descifró recursos protegidos (no publica la clave) y bajó los APK de espejos no oficiales. **Según tus reglas, el descifrado queda fuera.**
- **Repos excluidos:**
  - `skshadan/claude-android-reverse`: documenta trampas en Subway Surfers.
  - incogbyte, DragonJAR, areclaw y mobsf-mcp: traen bypass de SSL pinning, RASP o root.
  - `/neutralize` de Simone y apktool-mcp: re-empaquetado.
  - Spam: `mmegasuporte/roblox-experience-pulse`.
- **Seguridad de skills:** hubo skills con puertas traseras. Lee cada SKILL.md antes de instalar. REA (morluto/rea) muestra números anómalos (416k★) y reescribe la configuración de MCP: no instalar sin revisarlo.
- **No verificado:** la frecuencia real de las reglas que describe ayoships. Hungry Studio, King y SYBO no respondieron (al 2026-10-27). TechRadar pide verificación independiente.

## Decisión propuesta
- **Guardar** el stack de ingeniería inversa: solo para tus propias apps, apps open source o CTFs.
- **Aplicar ahora:** Exodus y PlayStore Radar para mirar juegos sin descompilar, y Roblox Configs + Experiments + Analytics para tus juegos.
- **Nada instalado todavía.** Si quieres probar, el primer candidato es `android-reverse-engineering-skill` sobre un APK tuyo o de código abierto.
