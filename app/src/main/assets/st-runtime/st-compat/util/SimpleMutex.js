// rikkaST st-compat: scripts/util/SimpleMutex.js
// Aligned with SillyTavern 1.19.0 public/scripts/util/SimpleMutex.js (also re-exported as ModuleWorkerWrapper).

export class SimpleMutex {
    isBusy = false;
    callback = () => {};

    constructor(callback) {
        this.isBusy = false;
        this.callback = typeof callback === 'function' ? callback : () => {};
    }

    async update(...args) {
        if (this.isBusy) {
            return;
        }
        try {
            this.isBusy = true;
            await this.callback(...args);
        } finally {
            this.isBusy = false;
        }
    }
}

export default SimpleMutex;
