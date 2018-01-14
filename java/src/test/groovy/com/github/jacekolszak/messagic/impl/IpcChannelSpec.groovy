package com.github.jacekolszak.messagic.impl

import com.github.jacekolszak.messagic.FatalError
import com.github.jacekolszak.messagic.MessageChannel
import com.github.jacekolszak.messagic.impl.Ipc
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class IpcChannelSpec extends Specification {

    private final PipedInputStream input = new PipedInputStream()
    private final PipedOutputStream inputPipe = new PipedOutputStream(input)
    private final ByteArrayOutputStream output = new ByteArrayOutputStream()

    private final Ipc ipc = new Ipc(input, output)

    @Subject
    private final MessageChannel channel = ipc.channel()

    void 'should create channel'() {
        expect:
            channel != null
    }

    void 'should always return same channel instance. It is not possible to have two different channels on same stdin and stdout'() {
        expect:
            channel == ipc.channel()
    }

    // PROTOKOL - CECHY
    // latwy w debugowaniu - tekst wskazany bez zadnych udziwnien
    // mozliwosc wyslania takze binarnych danych
    // mozliwosc debugowania co jest wysylane binarnie (Base64)
    // mozliwosc latwego parsowania
    // mozliwosc strzelania z palca do serwera (uruchamiamy w idei i piszemy do konsoli - sic!)

    // wiadomoscTekstowa\n
    // #sdw23=\n   -- binarna wiadomosc w base64
    // """wiadomosc \ntekstowa"""\n   - tekstowa wiadomosc z enterami
    // !blad
    void 'should send text message to stream'() {
        when:
            channel.pushText('textMessage')
        then:
            output.toString() == 'textMessage\n'
    }

    void 'binary messages should be encoded using base64 with "#" character as a prefix and new line in the end'() {
        when:
            channel.pushBinary([1, 2, 3] as byte[])
        then:
            output.toString() == '#AQID\n'
    }

    void 'should parse text message and pass it to consumer'() {
        given:
            CountDownLatch latch = new CountDownLatch(1)
            String messageReceived = null
            channel.textMessageConsumer = { msg ->
                messageReceived = msg
                latch.countDown()
            }
            channel.open()
        when:
            inputPipe.write('textMessage\n'.bytes)
        then:
            latch.await(2, TimeUnit.SECONDS)
            messageReceived == 'textMessage'
    }

    void 'should parse binary message and pass it to consumer'() {
        given:
            CountDownLatch latch = new CountDownLatch(1)
            byte[] messageReceived = null
            channel.binaryMessageConsumer = { msg ->
                messageReceived = msg
                latch.countDown()
            }
            channel.open()
        when:
            inputPipe.write('#AQID\n'.bytes) // #123\n
        then:
            latch.await(2, TimeUnit.SECONDS)
            messageReceived == [1, 2, 3] as byte[]
    }

    void 'should send error to sender when binary message cannot be parsed'() {
        given:
            channel.open()
        when:
            inputPipe.write('#@$%\n'.bytes)
        then:
            Thread.sleep(1000) // TODO ło matulu
            output.toString().startsWith('!Bad encoding of incoming binary message: ')
    }

    void 'should convert exception thrown by consumer to error text message'() {
        given:
            CountDownLatch latch = new CountDownLatch(1)
            channel.textMessageConsumer = { msg ->
                if (msg == 'messageCausingError') {
                    throw new RuntimeException("Deliberate exception")
                } else {
                    latch.countDown()
                }
            }
            channel.open()
        when:
            inputPipe.write('messageCausingError\n'.bytes)
            inputPipe.write('other\n'.bytes)
        then:
            latch.await(2, TimeUnit.SECONDS)
            output.toString() == '!Deliberate exception\n'
    }

    void 'should parse error'() {
        given:
            CountDownLatch latch = new CountDownLatch(1)
            ErrorConsumerMock errorConsumer = new ErrorConsumerMock(latch)
            channel.errorConsumer = errorConsumer
            channel.open()
        when:
            inputPipe.write('!Some error\n'.bytes)
        then:
            latch.await(2, TimeUnit.SECONDS)
            FatalError errorReceived = errorConsumer.errorReceived
            errorReceived.isPeerError()
            errorReceived.message() == 'Some error'
            !errorReceived.isPeerNotReachable()
    }

    void 'when could not read from input stream then PeerNotReachable error should be reported'() {
        given:
            CountDownLatch latch = new CountDownLatch(1)
            ErrorConsumerMock errorConsumer = new ErrorConsumerMock(latch)
            channel.errorConsumer = errorConsumer
            channel.open()
        when:
            inputPipe.close()
        then:
            latch.await(2, TimeUnit.SECONDS)
            errorConsumer.errorReceived.isPeerNotReachable()
    }

    void 'when could not write text to output stream then PeerNotReachable error should be reported'() {
        given:
            PipedOutputStream out = new PipedOutputStream()
            PipedInputStream outputPipe = new PipedInputStream(out)
            Ipc ipc = new Ipc(input, out)
            MessageChannel channel = ipc.channel()
            CountDownLatch latch = new CountDownLatch(1)
            ErrorConsumerMock errorConsumer = new ErrorConsumerMock(latch)
            channel.errorConsumer = errorConsumer
            channel.open()
        when:
            outputPipe.close()
            channel.pushText('test')
        then:
            latch.await(2, TimeUnit.SECONDS)
            errorConsumer.errorReceived.isPeerNotReachable()
    }

    void 'when could not write binary to output stream then PeerNotReachable error should be reported'() {
        given:
            PipedOutputStream out = new PipedOutputStream()
            PipedInputStream outputPipe = new PipedInputStream(out)
            Ipc ipc = new Ipc(input, out)
            MessageChannel channel = ipc.channel()
            CountDownLatch latch = new CountDownLatch(1)
            ErrorConsumerMock errorConsumer = new ErrorConsumerMock(latch)
            channel.errorConsumer = errorConsumer
            channel.open()
        when:
            outputPipe.close()
            channel.pushBinary([1] as byte[])
        then:
            latch.await(2, TimeUnit.SECONDS)
            errorConsumer.errorReceived.isPeerNotReachable()
    }

    void 'should report error and close the channel when pushed binary message was big'() {
        given:
            CountDownLatch latch = new CountDownLatch(1)
            channel.binaryMessageMaximumSize = 16
            ErrorConsumerMock errorConsumer = new ErrorConsumerMock(latch)
            channel.errorConsumer = errorConsumer
            channel.open()
        when:
            channel.pushBinary(new byte[17])
        then:
            latch.await(2, TimeUnit.SECONDS)
            FatalError errorReceived = errorConsumer.errorReceived
            errorReceived.isPeerNotReachable()
            errorReceived.message() == 'Payload of pushed binary message exceeded maximum size'
    }

    void 'should report error and close the channel when pushed text message was big'() {
        given:
            CountDownLatch latch = new CountDownLatch(1)
            channel.textMessageMaximumSize = 5
            ErrorConsumerMock errorConsumer = new ErrorConsumerMock(latch)
            channel.errorConsumer = errorConsumer
            channel.open()
        when:
            channel.pushText('123456')
        then:
            latch.await(2, TimeUnit.SECONDS)
            FatalError errorReceived = errorConsumer.errorReceived
            errorReceived.isPeerNotReachable()
            errorReceived.message() == 'Payload of pushed text message exceeded maximum size'
    }

    void 'should report and close the channel when received binary message was too big'() {
        given:
            CountDownLatch latch = new CountDownLatch(1)
            channel.binaryMessageMaximumSize = 2
            ErrorConsumerMock errorConsumer = new ErrorConsumerMock(latch)
            channel.errorConsumer = errorConsumer
            channel.open()
        when:
            inputPipe.write('#AQID\n'.bytes) // 3 bytes that is [1,2,3]
        then:
            latch.await(2, TimeUnit.SECONDS)
            FatalError errorReceived = errorConsumer.errorReceived
            errorReceived.isPeerNotReachable()
            errorReceived.message() == 'Payload of received binary message exceeded maximum size'
    }

    void 'should report and close the channel when received text message was too big'() {
        given:
            CountDownLatch latch = new CountDownLatch(1)
            channel.textMessageMaximumSize = 2
            ErrorConsumerMock errorConsumer = new ErrorConsumerMock(latch)
            channel.errorConsumer = errorConsumer
            channel.open()
        when:
            inputPipe.write('123\n'.bytes)
        then:
            latch.await(2, TimeUnit.SECONDS)
            FatalError errorReceived = errorConsumer.errorReceived
            errorReceived.isPeerNotReachable()
            errorReceived.message() == 'Payload of received text message exceeded maximum size'
    }

    void 'should report and close the channel when received error message was too big'() {
        given:
            CountDownLatch latch = new CountDownLatch(1)
            channel.textMessageMaximumSize = 2
            ErrorConsumerMock errorConsumer = new ErrorConsumerMock(latch)
            channel.errorConsumer = errorConsumer
            channel.open()
        when:
            inputPipe.write('!123\n'.bytes)
        then:
            latch.await(2, TimeUnit.SECONDS)
            FatalError errorReceived = errorConsumer.errorReceived
            errorReceived.isPeerNotReachable()
            errorReceived.message() == 'Payload of received error message exceeded maximum size'
    }

//
//    void 'request/response example'() {
//        given:
//            Ipc ipc = new Ipc(System.in, System.out)
//            MessageChannel channel = ipc.channel()
//            RequestResponsePattern pattern = new RequestResponsePattern(channel) // retry dzieje sie w warstwie transportowej websocketow, timeouty nie potrzebne bo to tez warstwa transportowa zalatwi
//        when:
//            CompletableFuture<byte[]> response = pattern.request('payload'.bytes)
//        then:
//            // dokleja numerek
//            // [numerek,':','w','i','a','d','o','m','o','s','c']
//
//            channel.setBinaryMessageConsumer { msg ->
//                RequestResponsePattern.MessageReceived request = pattern.requestReceived(msg)
//                request.respondWith('some repsonse'.bytes) // jesli nie bylo numerka to odpowiada bez numerka
//            }
//    }

    class ErrorConsumerMock implements Consumer<FatalError> {

        FatalError errorReceived = null
        final CountDownLatch latch

        ErrorConsumerMock(CountDownLatch latch) {
            this.latch = latch
        }

        @Override
        void accept(FatalError fatalError) {
            errorReceived = fatalError
            latch.countDown()
        }

    }

}
