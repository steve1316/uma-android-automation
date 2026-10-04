// Intermediate Kotlin/JS package.json files under android/build and android/scoring-shared/build/tmp confuse Jest's haste map: they look like duplicate copies of the
// `uma-scoring` package next to the real published library under productionLibrary. Excluding them from Jest's module path resolution keeps `import "uma-scoring"`
// unambiguous (it always resolves through node_modules to the productionLibrary copy).
const ignoreKotlinJsIntermediates = ["<rootDir>/android/build/", "<rootDir>/android/scoring-shared/build/tmp/", "<rootDir>/android/scoring-shared/build/js/"]

module.exports = {
    modulePathIgnorePatterns: ignoreKotlinJsIntermediates,
    haste: {
        retainAllFiles: false,
    },
    projects: [
        {
            displayName: "node",
            testMatch: ["<rootDir>/src/**/*.test.ts", "<rootDir>/scripts/**/*.test.ts"],
            modulePathIgnorePatterns: ignoreKotlinJsIntermediates,
            moduleNameMapper: {
                "^@/(.*)$": "<rootDir>/$1",
                // expo-file-system ships ESM that this project cannot parse. The live data modules import it, so map it to a stub.
                "^expo-file-system$": "<rootDir>/jest/expoFileSystemStub.js",
            },
            modulePaths: ["<rootDir>/src"],
            transform: {
                "^.+\\.(ts|tsx)$": [
                    "babel-jest",
                    {
                        // Skip babel.config.js so babel-preset-expo doesn't rewrite `process.env.EXPO_PUBLIC_*` into imports of `expo/virtual/env.js`, which Jest can't parse.
                        configFile: false,
                        babelrc: false,
                        presets: [
                            ["@babel/preset-env", { targets: { node: "current" } }],
                            "@babel/preset-typescript",
                        ],
                    },
                ],
            },
        },
        {
            displayName: "components",
            preset: "jest-expo",
            testMatch: ["<rootDir>/src/**/*.test.tsx"],
            modulePathIgnorePatterns: ignoreKotlinJsIntermediates,
            moduleNameMapper: {
                "^@/(.*)$": "<rootDir>/$1",
            },
            modulePaths: ["<rootDir>/src"],
        },
    ],
    collectCoverageFrom: [
        "src/lib/eventLogParser.ts",
        "src/lib/settingsUtils.ts",
        "src/lib/logger.ts",
        "src/lib/performanceLogger.ts",
        "src/components/**/helpers.ts",
        "src/context/searchConfig.ts",
    ],
}
