#!/usr/bin/env node
// Revisa que la app use el sistema de diseño (docs/design/tokens.json): cada color de la paleta
// y de las categorías tiene que estar en Ui.kt / Sounds.kt con el mismo valor.
//
//   node tools/design/check-tokens.mjs      → sale con 0 si todo coincide, 1 si algo no.
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const repo = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const read = (path) => readFileSync(join(repo, path), "utf8");
const tokens = JSON.parse(read("docs/design/tokens.json"));
const ui = read("app/src/main/java/com/friendgame/app/Ui.kt");
const sounds = read("app/src/main/java/com/friendgame/app/Sounds.kt");

/** "#FF4FA3" → "0xFFFF4FA3", como se escribe en Kotlin. */
const kotlin = (hex) => "0xFF" + hex.replace("#", "").toUpperCase();
const problems = [];

for (const [name, hex] of Object.entries(tokens.colors)) {
  const pattern = new RegExp(`val ${name} = (0x[0-9A-F]{8})`);
  const found = ui.match(pattern);
  if (!found) problems.push(`Ui.kt no tiene Palette.${name}`);
  else if (found[1] !== kotlin(hex)) problems.push(`Palette.${name} es ${found[1]} y el diseño dice ${kotlin(hex)}`);
}

for (const [key, category] of Object.entries(tokens.categories)) {
  const pattern = new RegExp(`Vibe\\("${key}"[^)]*?"${category.label}",\\s*(0x[0-9A-F]{8}).toInt\\(\\), (0x[0-9A-F]{8})`, "s");
  const found = sounds.match(pattern);
  if (!found) problems.push(`Sounds.kt no tiene la categoría ${key} (${category.label})`);
  else {
    if (found[1] !== kotlin(category.color)) problems.push(`${key}: color ${found[1]}, el diseño dice ${kotlin(category.color)}`);
    if (found[2] !== kotlin(category.lip)) problems.push(`${key}: labio ${found[2]}, el diseño dice ${kotlin(category.lip)}`);
  }
}

if (problems.length) {
  console.error("❌ La app no sigue el sistema de diseño:\n- " + problems.join("\n- "));
  process.exit(1);
}
console.log(`✅ ${Object.keys(tokens.colors).length} colores y ${Object.keys(tokens.categories).length} categorías coinciden con el diseño.`);
