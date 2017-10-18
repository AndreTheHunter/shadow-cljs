
import Something, { Foo } from "./thing";

const PI = 3.141593

class Shape {
    constructor (id, x, y) {
        this.id = id
        this.move(x, y)
    }
    move (x, y) {
        this.x = x
        this.y = y
    }
}

export { PI };

export default Shape;

/*
// needs regenerator runtime, whatever that is
function loadExternalContent() {
    return new Promise((resolve, reject) => {
        setTimeout(() => {
            resolve('hello');
        }, 3000);
    });
}
async function getContent() {
    const text = await loadExternalContent();
    console.log(text);
}
*/