/**
MIT License

Copyright (c) 2018 White Wood City

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

package cn.net.polyglot.verticle

import cn.net.polyglot.config.*
import com.google.common.collect.HashBiMap
import io.vertx.core.AbstractVerticle
import io.vertx.core.buffer.Buffer
import io.vertx.core.file.OpenOptions
import io.vertx.core.json.JsonObject
import io.vertx.core.net.NetServerOptions
import io.vertx.core.net.NetSocket
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.experimental.launch
import java.io.File

class IMTcpServerVerticle : AbstractVerticle() {

  private val socketMap = HashBiMap.create<NetSocket, String>()
  private var buffer = Buffer.buffer()

  override fun start() {

    val port = config().getInteger(TCP_PORT)

    vertx.eventBus().consumer<JsonObject>(this::class.java.name) {
      val type = it.body().getString(TYPE)
      val target = it.body().getString(TO)
      if (socketMap.containsValue(target)) {
        socketMap.inverse()[target]!!.write(it.body().toString().plus(END))
      } else if(type == MESSAGE){//仅是message类型的时候，投递不成功会在此处存入硬盘，friend类型已经先行处理
        val targetDir = config().getString(DIR) + File.separator + it.body().getString(TO) + File.separator + ".message"
        val fs = vertx.fileSystem()
        if (!fs.existsBlocking(targetDir)) fs.mkdirBlocking(targetDir)
        if (!fs.existsBlocking("$targetDir${File.separator}${it.body().getString(FROM)}.sv"))
          fs.createFileBlocking("$targetDir${File.separator}${it.body().getString(FROM)}.sv")
        fs.openBlocking("$targetDir${File.separator}${it.body().getString(FROM)}.sv", OpenOptions().setAppend(true))
          .write(it.body().toBuffer().appendString(END))
      }
    }
    vertx.createNetServer(NetServerOptions().setTcpKeepAlive(true)).connectHandler { socket ->

      //因为是bimap，不能重复存入null，会抛异常，所以临时先放一个字符串，等用户登陆之后便会替换该字符串，以用户名取代
      socketMap[socket] = socket.writeHandlerID()

      socket.handler {
        buffer.appendBuffer(it)
        if (buffer.toString().endsWith(END)) {
          val msgs = buffer.toString().substringBeforeLast(END).split(END)
          for (s in msgs) {
            processJsonString(s, socket)
          }
          buffer = Buffer.buffer()
        }

        if (buffer.length() > 1 * 1048576L) {
          buffer = Buffer.buffer()
        }
      }

      socket.closeHandler {
        socketMap.remove(socket)
      }

      socket.exceptionHandler { e ->
        e.printStackTrace()
        socket.close()
      }
    }.listen(port) {
      if (it.succeeded()) {
        println("${this::class.java.name} is deployed")
      } else {
        println("${this::class.java.name} fail to deploy")
      }
    }
  }

  private fun processJsonString(jsonString: String, socket: NetSocket) {
    val result = JsonObject()
    try {
      val json = JsonObject(jsonString).put(FROM, socketMap[socket])
      when (json.getString(TYPE)) {
        USER, SEARCH -> {
          vertx.eventBus().send<JsonObject>(IMMessageVerticle::class.java.name, json) {
            val jsonObject = it.result().body()
            if (jsonObject.containsKey(LOGIN)&&jsonObject.getBoolean(LOGIN)){
              if(socketMap.containsValue(json.getString(ID))&& socketMap.inverse()[json.getString(ID)] != socket){
                socketMap.inverse()[json.getString(ID)]?.close()//表示之前连接的socket跟当前socket不是一个，设置单点登录
              }
              socketMap[socket] = json.getString(ID)
            }
            jsonObject.mergeIn(json).remove(PASSWORD)
            jsonObject.remove(FROM)
            socket.write(jsonObject.toString().plus(END))
          }
        }
        else -> {
          vertx.eventBus().send(IMMessageVerticle::class.java.name, json)
        }
      }
    } catch (e: Exception) {
      socket.write(result.put(INFO, "${e.message}").toString().plus(END))
    }
  }
}
