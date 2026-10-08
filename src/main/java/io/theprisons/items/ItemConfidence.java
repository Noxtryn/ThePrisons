package io.theprisons.items;

/** How an item was recognised: by the server's own item id, by its name only, or not at all (a plain vanilla item). */
public enum ItemConfidence {
    ID, NAME, VANILLA
}
