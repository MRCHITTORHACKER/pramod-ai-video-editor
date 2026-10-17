#!/usr/bin/env python3
"""Generate module directories + build.gradle.kts for every module.
Run from the project root: python3 tools/gen_modules.py
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = "com.pramod.videoeditor"

# module -> config
MODULES = {
    "app":               dict(kind="app", compose=True,  deps=["core-model","core-database","core-storage","core-security","feature-home","feature-projects","feature-editor","feature-captions","feature-typography","feature-templates","feature-audio","feature-vfx","feature-export","ai-groq","ai-gemini","media-engine"]),
    "core-model":        dict(kind="lib", deps=[]),
    "core-database":     dict(kind="lib", room=True, deps=["core-model"]),
    "core-storage":      dict(kind="lib", deps=["core-model"]),
    "core-security":     dict(kind="lib", deps=[]),
    "feature-home":      dict(kind="lib", compose=True, deps=["core-model"]),
    "feature-projects":  dict(kind="lib", compose=True, deps=["core-model","core-database","core-storage"]),
    "feature-editor":    dict(kind="lib", compose=True, deps=["core-model","feature-templates","feature-audio","feature-vfx","feature-captions","feature-typography","ai-groq","ai-gemini","media-engine"]),
    "feature-captions":  dict(kind="lib", compose=True, deps=["core-model"]),
    "feature-typography":dict(kind="lib", compose=True, deps=["core-model"]),
    "feature-templates": dict(kind="lib", compose=True, deps=["core-model"]),
    "feature-audio":     dict(kind="lib", compose=True, deps=["core-model"]),
    "feature-vfx":       dict(kind="lib", compose=True, deps=["core-model"]),
    "feature-export":    dict(kind="lib", compose=True, deps=["core-model","core-database","media-engine"]),
    "ai-groq":           dict(kind="lib", deps=[]),
    "ai-gemini":         dict(kind="lib", deps=[]),
    "media-engine":      dict(kind="lib", media3=True, deps=["core-model"]),
}

LIB_DEPS = {
    "core-model": [
        'implementation(libs.kotlinx.serialization.json)',
    ],
    "core-database": [
        'implementation(libs.room.runtime)',
        'implementation(libs.room.ktx)',
        'kapt(libs.room.compiler)',
    ],
    "core-storage": [
        'implementation(libs.androidx.core.ktx)',
    ],
    "core-security": [
        'implementation(libs.security.crypto)',
    ],
    "feature-templates": [
        'implementation(libs.kotlinx.serialization.json)',
        'implementation(libs.kotlinx.coroutines.android)',
    ],
    "ai-groq": [
        'implementation(libs.okhttp)',
        'implementation(libs.kotlinx.serialization.json)',
        'implementation(libs.kotlinx.coroutines.android)',
    ],
    "ai-gemini": [
        'implementation(libs.okhttp)',
        'implementation(libs.kotlinx.serialization.json)',
        'implementation(libs.kotlinx.coroutines.android)',
    ],
    "media-engine": [
        'implementation(libs.media3.transformer)',
        'implementation(libs.media3.exoplayer)',
        'implementation(libs.media3.common)',
        'implementation(libs.media3.effect)',
        'implementation(libs.media3.datasource)',
        'implementation(libs.kotlinx.coroutines.android)',
    ],
}

COMPOSE_DEPS = [
    'implementation(platform(libs.compose.bom))',
    'implementation(libs.compose.ui)',
    'implementation(libs.compose.material3)',
    'implementation(libs.compose.ui.tooling.preview)',
    'implementation(libs.compose.material.icons.extended)',
    'implementation(libs.androidx.activity.compose)',
    'implementation(libs.lifecycle.viewmodel.compose)',
    'implementation(libs.lifecycle.runtime.compose)',
    'implementation(libs.navigation.compose)',
    'implementation(libs.kotlinx.coroutines.android)',
    'debugImplementation(libs.compose.ui.tooling)',
]

APP_EXTRA_DEPS = [
    'implementation(libs.core.splashscreen)',
    'implementation(libs.coil.compose)',
    'implementation(libs.datastore.preferences)',
    'implementation(libs.media3.ui)',
    'implementation(libs.media3.exoplayer)',
    'implementation(libs.media3.common)',
]


def android_block(namespace, compose):
    lines = ["android {",
             f'    namespace = "{namespace}"',
             "    compileSdk = 36",
             "",
             "    defaultConfig {",
             "        minSdk = 26",
             "    }" if True else "",
             "",
             "    compileOptions {",
             "        sourceCompatibility = JavaVersion.VERSION_17",
             "        targetCompatibility = JavaVersion.VERSION_17",
             "    }",
             "    kotlin {",
             "        compilerOptions {",
             "            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)",
             "        }",
             "    }"]
    if compose:
        lines.append("    buildFeatures {")
        lines.append("        compose = true")
        lines.append("    }")
    lines.append("}")
    return "\n".join(lines)


def write_module(name, cfg):
    mod_dir = os.path.join(ROOT, name)
    src = os.path.join(mod_dir, "src", "main", "java",
                       *PKG.split("."), name.replace("-", ""))
    os.makedirs(src, exist_ok=True)
    # keep a marker so empty source dirs are visible
    open(os.path.join(src, ".keep"), "w").write("")

    if cfg["kind"] == "app":
        plugins = ('plugins {\n'
                   '    alias(libs.plugins.android.application)\n'
                   '    alias(libs.plugins.kotlin.android)\n'
                   '    alias(libs.plugins.kotlin.serialization)\n'
                   '}')
        android = "\n".join([
            "android {",
            f'    namespace = "{PKG}"',
            "    compileSdk = 36",
            "",
            "    defaultConfig {",
            f'        applicationId = "{PKG}"',
            "        minSdk = 26",
            "        targetSdk = 36",
            "        versionCode = 1",
            '        versionName = "1.0.0"',
            "    }",
            "",
            "    buildTypes {",
            "        release {",
            "            isMinifyEnabled = false",
            '            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")',
            "        }",
            "    }",
            "    compileOptions {",
            "        sourceCompatibility = JavaVersion.VERSION_17",
            "        targetCompatibility = JavaVersion.VERSION_17",
            "    }",
            "    kotlin {",
            "        compilerOptions {",
            "            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)",
            "        }",
            "    }",
            "    buildFeatures {",
            "        compose = true",
            "        buildConfig = true",
            "    }",
            "}",
        ])
        deps = (["    // inter-module"] +
                [f'    implementation(project(":{d}"))' for d in cfg["deps"]] +
                ["    // compose"] + ["    " + d for d in COMPOSE_DEPS] +
                ["    // app extras"] + ["    " + d for d in APP_EXTRA_DEPS])
    else:
        plugins = ('plugins {\n'
                   '    alias(libs.plugins.android.library)\n'
                   '    alias(libs.plugins.kotlin.android)\n'
                   + ('    alias(libs.plugins.kotlin.kapt)\n' if cfg.get("room") else '') +
                   '    alias(libs.plugins.kotlin.serialization)\n'
                   '}')
        android = android_block(f"{PKG}.{name.replace('-', '')}", cfg.get("compose", False))
        deps = (["    // inter-module"] +
                [f'    implementation(project(":{d}"))' for d in cfg.get("deps", [])] +
                (["    // module"] + ["    " + d for d in LIB_DEPS.get(name, [])] if name in LIB_DEPS else []) +
                (["    // compose"] + ["    " + d for d in COMPOSE_DEPS] if cfg.get("compose") else []))

    content = plugins + "\n\n" + android + "\n\ndependencies {\n" + "\n".join(deps) + "\n}\n"
    with open(os.path.join(mod_dir, "build.gradle.kts"), "w") as f:
        f.write(content)
    # consumer proguard placeholder for app
    if cfg["kind"] == "app":
        open(os.path.join(mod_dir, "proguard-rules.pro"), "w").write(
            "# Add project-specific ProGuard rules here.\n")


def main():
    for name, cfg in MODULES.items():
        write_module(name, cfg)
    print(f"generated {len(MODULES)} modules")


if __name__ == "__main__":
    main()
