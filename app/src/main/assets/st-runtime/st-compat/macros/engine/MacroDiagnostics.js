/**
 * rikkaST st-compat: macros/engine/MacroDiagnostics.js
 */
export const onboardingExperimentalMacroEngineMutex = {
    update: async (fn) => (typeof fn === 'function' ? await fn() : undefined),
};
export const onboardingExperimentalMacroEngine = onboardingExperimentalMacroEngineMutex.update;
export function createMacroRuntimeError({ message } = {}) { return new Error(String(message || 'Macro runtime error')); }
export function logMacroRuntimeWarning() {}
export function logMacroInternalError() {}
export function logMacroRegisterWarning() {}
export function logMacroRegisterError() {}
export function logMacroGeneralError() {}
export function logMacroSyntaxWarning() {}
