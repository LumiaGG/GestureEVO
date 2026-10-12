package io.github.lumiagg.gestureevo.db

import androidx.room.Entity

@Entity()
class ContactModel() : BaseModel() {

    var contactId: Long = 0
    var phoneNumber: String = ""
    var photoUri: String? = null

    constructor(name: String, pkg: String) : this() {
        this.name = name
    }
}
