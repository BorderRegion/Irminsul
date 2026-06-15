package com.github.quiltservertools.ledger.network.packet.receiver

import com.github.quiltservertools.ledger.Ledger
import com.github.quiltservertools.ledger.actionutils.RollbackOperations
import com.github.quiltservertools.ledger.commands.CommandConsts
import com.github.quiltservertools.ledger.commands.arguments.SearchParamArgument
import com.github.quiltservertools.ledger.network.packet.LedgerPacketTypes
import com.github.quiltservertools.ledger.network.packet.response.ResponseCodes
import com.github.quiltservertools.ledger.network.packet.response.ResponseContent
import com.github.quiltservertools.ledger.network.packet.response.ResponseS2CPacket
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

data class RollbackC2SPacket(val restore: Boolean, val input: String) : CustomPayload {

    override fun getId() = ID

    companion object : ServerPlayNetworking.PlayPayloadHandler<RollbackC2SPacket> {
        val ID: CustomPayload.Id<RollbackC2SPacket> = CustomPayload.Id(LedgerPacketTypes.ROLLBACK.id)
        val CODEC: PacketCodec<PacketByteBuf, RollbackC2SPacket> = CustomPayload.codecOf({ _, _ -> TODO() }, {
            RollbackC2SPacket(it.readBoolean(), it.readString())
        })

        @Suppress("TooGenericExceptionCaught")
        override fun receive(payload: RollbackC2SPacket, context: ServerPlayNetworking.Context) {
            val player = context.player()
            val sender = context.responseSender()
            if (!Permissions.check(player, "ledger.networking", CommandConsts.PERMISSION_LEVEL) ||
                !Permissions.check(player, "ledger.commands.rollback", CommandConsts.PERMISSION_LEVEL)
            ) {
                ResponseS2CPacket.sendResponse(
                    ResponseContent(LedgerPacketTypes.ROLLBACK.id, ResponseCodes.NO_PERMISSION.code),
                    sender
                )
                return
            }

            val params = try {
                SearchParamArgument.get(payload.input, player.commandSource).also { it.ensureSpecific() }
            } catch (throwable: Throwable) {
                ResponseS2CPacket.sendResponse(
                    ResponseContent(LedgerPacketTypes.ROLLBACK.id, ResponseCodes.ERROR.code),
                    sender
                )
                return
            }

            ResponseS2CPacket.sendResponse(
                ResponseContent(LedgerPacketTypes.ROLLBACK.id, ResponseCodes.EXECUTING.code),
                sender
            )

            Ledger.launch {
                try {
                    val mode = if (payload.restore) {
                        RollbackOperations.Mode.RESTORE
                    } else {
                        RollbackOperations.Mode.ROLLBACK
                    }
                    val result = RollbackOperations.execute(player.commandSource.server, params, mode)
                    val responseCode = when {
                        result.requestedActions == 0 -> ResponseCodes.NO_RESULTS
                        result.successfulActionIds.size == result.requestedActions -> ResponseCodes.COMPLETED
                        else -> ResponseCodes.PARTIAL
                    }
                    ResponseS2CPacket.sendResponse(
                        ResponseContent(LedgerPacketTypes.ROLLBACK.id, responseCode.code),
                        sender
                    )
                } catch (throwable: Throwable) {
                    Ledger.logger.warn("Network Ledger rollback failed", throwable)
                    ResponseS2CPacket.sendResponse(
                        ResponseContent(LedgerPacketTypes.ROLLBACK.id, ResponseCodes.ERROR.code),
                        sender
                    )
                }
            }
        }
    }
}
