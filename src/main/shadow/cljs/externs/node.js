/**
 * these things are in cljs.core only they are only relevant to self-hosted
 * hopefully they will be moved out in the future
 *
 * @externs
 */

Error.prototype.number;
Error.prototype.columnNumber;

/**
 * @const
 */
var global;

/** @const {string} */
var __filename;

/** @const {string} */
var __dirname;

/**
 * @param {string} name
 * @return {ShadowJS}
 */
function require(name) {}


/**
 * FIXME: temporary placeholder until I can do better
 */
function shadow$placeholder(name) {}