import {LitElement, html, css} from 'lit';

// The still frame replaces the animation for reduced motion.
const GOBLIN_ANIMATED = new URL('goblin-active.gif', import.meta.url).href;
const GOBLIN_STILL = new URL('goblin-active.png', import.meta.url).href;

/**
 * The goblin casting a spell in the bottom-right corner of a Goblin page while chaos is active. Shared by the Chaos
 * Dashboard and the History page, each of which passes the active flag it reads from the engine.
 */
export class GoblinActive extends LitElement {

    static styles = css`
        picture {
            position: fixed;
            right: 24px;
            bottom: 48px;
            z-index: 10;
            pointer-events: none;
        }
        img {
            display: block;
            width: 130px;
            height: 138px;
            image-rendering: pixelated;
        }
    `;

    static properties = {
        active: {type: Boolean},
    };

    constructor() {
        super();
        this.active = false;
    }

    render() {
        return this.active ? html`
            <picture>
                <source srcset="${GOBLIN_STILL}" media="(prefers-reduced-motion: reduce)">
                <img src="${GOBLIN_ANIMATED}" alt="Goblin casting a spell: chaos is active">
            </picture>` : '';
    }
}

customElements.define('goblin-active', GoblinActive);
