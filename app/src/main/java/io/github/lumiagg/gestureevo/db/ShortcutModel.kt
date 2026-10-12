package io.github.lumiagg.gestureevo.db

import androidx.room.Entity

@Entity()
class ShortcutModel() : BaseModel() {

    var uri: String? = null

    constructor(name: String, pkg: String) : this() {
        this.name = name
    }
}
