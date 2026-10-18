package com.friendgame.app

import org.json.JSONObject

/**
 * Acciones sobre grupos. Las que hablan con el servidor deben llamarse fuera del hilo de la
 * pantalla. Para sacar a alguien se cambia el canal del grupo y solo los que se quedan reciben
 * el nuevo (firmado por el admin): quien sale ya no puede escuchar ni mandar nada al grupo.
 */
object Groups {

    fun create(name: String, community: Boolean = false, emoji: String = ""): Group {
        val (publicKey, secret) = GroupKeys.newKeyPair()
        val group = Group(
            id = Codes.newCode(),
            name = name.trim().take(24),
            adminCode = Store.myCode,
            adminKey = publicKey,
            adminSecret = secret,
            members = listOf(Member(Store.myCode, Store.myName)),
            community = community,
            emoji = emoji,
        )
        Store.putGroup(group)
        Points.onGroupJoined(group.id)
        return group
    }

    /** Guarda el grupo en este celular. Después hay que llamar a [announceJoin]. */
    fun join(invite: Invites.Invite): Group {
        val group = Store.group(invite.groupId) ?: Group(
            id = invite.groupId,
            name = invite.name,
            adminCode = invite.adminCode,
            adminKey = invite.adminKey,
            members = listOf(Member(Store.myCode, Store.myName)),
            community = invite.community,
            emoji = invite.emoji,
        )
        Store.putGroup(group)
        Points.onGroupJoined(group.id)
        return group
    }

    fun announceJoin(group: Group) {
        Relay.publishTopic(Relay.groupTopic(group.id), Relay.payload("gjoin").put("g", group.id))
    }

    fun leave(group: Group) {
        Store.removeGroup(group.id)
        val (topic, message) = goodbye(group)
        Relay.publishTopic(topic, message)
    }

    /**
     * Mensaje para irse de un grupo: el admin lo cierra para todos y los demás avisan que salen.
     * Se arma antes de cambiar de código, porque lleva el código de quien lo manda.
     */
    fun goodbye(group: Group): Pair<String, JSONObject> {
        val message = if (group.isAdmin) Relay.signed("gend", JSONObject().put("g", group.id), group.adminSecret)
        else Relay.payload("gleave").put("g", group.id)
        return Relay.groupTopic(group.id) to message
    }

    /** Admin: manda a todos la lista de miembros y el nombre, firmados. */
    fun publishRoster(group: Group) {
        if (!group.isAdmin) return
        val body = JSONObject()
            .put("g", group.id)
            .put("name", group.name)
            .put("members", Store.membersJson(group.members))
            .put("c", group.community)
            .put("e", group.emoji)
        Relay.publishTopic(Relay.groupTopic(group.id), Relay.signed("groster", body, group.adminSecret))
    }

    fun rename(group: Group, name: String) {
        val renamed = group.copy(name = name.trim().take(24))
        Store.putGroup(renamed)
        publishRoster(renamed)
    }

    /** Admin: saca a [member]. Devuelve el grupo con su canal nuevo. */
    fun remove(group: Group, member: Member): Group {
        val remaining = group.members.filter { it.code != member.code }
        val moved = group.copy(id = Codes.newCode(), members = remaining)
        val moveBody = JSONObject()
            .put("g", group.id)
            .put("ng", moved.id)
            .put("name", group.name)
            .put("members", Store.membersJson(remaining))
        val move = Relay.signed("gmove", moveBody, group.adminSecret)
        Store.moveGroup(group.id, moved)
        remaining.filter { it.code != Store.myCode }.forEach { other ->
            try {
                Relay.publish(other.code, move)
            } catch (_: Exception) {
                // Si alguien no recibe el canal nuevo, el admin puede volver a invitarlo con el QR.
            }
        }
        val kickBody = JSONObject().put("g", group.id).put("name", group.name)
        Relay.publish(member.code, Relay.signed("gkick", kickBody, group.adminSecret))
        return moved
    }

    /** Admin: cierra el grupo para todos. */
    fun close(group: Group) {
        Store.removeGroup(group.id)
        val (topic, message) = goodbye(group)
        Relay.publishTopic(topic, message)
    }
}
