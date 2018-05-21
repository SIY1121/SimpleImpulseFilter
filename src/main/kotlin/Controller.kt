import javafx.application.Platform
import javafx.event.ActionEvent
import javafx.fxml.FXML
import javafx.scene.Node
import javafx.scene.canvas.Canvas
import javafx.scene.control.TextField
import javafx.scene.paint.Color
import javafx.stage.FileChooser
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.FrameGrabber
import org.jtransforms.fft.FloatFFT_1D
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

class Controller {
    @FXML
    lateinit var srcCanvas: Canvas
    @FXML
    lateinit var irCanvas: Canvas
    @FXML
    lateinit var irSpectrumCanvas: Canvas
    @FXML
    lateinit var impulsePathTextField: TextField
    @FXML
    lateinit var srcPathTextField: TextField

    var srcGrabber: FFmpegFrameGrabber? = null

    var irSamples = FloatArray(0)
    fun onImpulseSelect(actionEvent: ActionEvent) {
        try {
            val file = FileChooser().showOpenDialog((actionEvent.source as Node).scene.window)
            impulsePathTextField.text = file.path
            if (file != null) {
                if (file.extension == "dat") {
                    val reader1 = BufferedReader(FileReader(file))
                    val raw1 = reader1.readText()
                    val data1 = raw1.split("\n").map { it.toDoubleOrNull() ?: 0.0 }.toDoubleArray()
                    val file2 = FileChooser().showOpenDialog((actionEvent.source as Node).scene.window)
                    val reader2 = BufferedReader(FileReader(file2))
                    val raw2 = reader2.readText()
                    val data2 = raw2.split("\n").map { it.toDoubleOrNull() ?: 0.0 }.toDoubleArray()
                    val max = Math.max(data1.max() ?: 1.0, data2.max() ?: 1.0)
                    irSamples = FloatArray(data1.size + data2.size)

                    for (i in 0 until irSamples.size - 1) {
                        irSamples[i] = if (i % 2 == 0) (data1[i / 2] / max).toFloat() else (data2[i / 2 + 1] / max).toFloat()
                    }
                } else {
                    val grabber = FFmpegFrameGrabber(file)
                    grabber.sampleMode = FrameGrabber.SampleMode.FLOAT
                    grabber.start()
                    irSamples = FloatArray(((grabber.lengthInTime / 1000_000.0) * grabber.sampleRate * grabber.audioChannels).toInt() + 1)
                    var read = 0

                    while (true) {
                        val buf = grabber.grabSamples()?.samples?.get(0) as? FloatBuffer ?: break
                        buf.get(irSamples, read, buf.limit())
                        read += buf.limit()
                    }
                    grabber.stop()
                }


                irSamples = FloatArray(irSamples.size) + irSamples
                drawWave(irSamples, irCanvas)
                FloatFFT_1D(irSamples.size.toLong()).realForward(irSamples)
                drawSpectrum(irSamples, irSpectrumCanvas)

            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }

    }

    fun onSourceSelect(actionEvent: ActionEvent) {
        val file = FileChooser().showOpenDialog((actionEvent.source as Node).scene.window)
        srcPathTextField.text = file.path
        if (file != null) {
            srcGrabber = FFmpegFrameGrabber(file)
            srcGrabber?.sampleMode = FrameGrabber.SampleMode.FLOAT
            srcGrabber?.start()
        }
    }

    private fun drawWave(data: FloatArray, canvas: Canvas) {
        val g = canvas.graphicsContext2D
        g.clearRect(0.0, 0.0, canvas.width, canvas.height)
        g.stroke = Color.BLACK
        data.forEachIndexed { index, value ->
            val x = index / data.size.toDouble() * canvas.width
            val y = (1.0 - value) * canvas.height
            g.strokeLine(x, y, x, canvas.height)
        }
    }

    private fun drawSpectrum(data: FloatArray, canvas: Canvas) {
        val g = canvas.graphicsContext2D
        g.clearRect(0.0, 0.0, canvas.width, canvas.height)
        g.stroke = Color.BLACK
        val distance = FloatArray(data.size / 2)
        for (i in 0 until distance.size) {
            distance[i] = Math.sqrt(Math.pow(data[i * 2].toDouble(), 2.0) + Math.pow(data[i * 2 + 1].toDouble(), 2.0)).toFloat()
        }
        val max = distance.max() ?: 1f
        distance.forEachIndexed { index, value ->
            val x = index / (data.size / 2.0) * canvas.width
            val y = (1.0 - value / max) * canvas.height
            g.strokeLine(x, y, x, canvas.height)
        }
    }

    fun play(actionEvent: ActionEvent) {

        val audioFormat = AudioFormat((srcGrabber?.sampleRate?.toFloat() ?: 0f), 16, 2, true, false)

        val info = DataLine.Info(SourceDataLine::class.java, audioFormat)
        val audioLine = AudioSystem.getLine(info) as SourceDataLine
        audioLine.open(audioFormat)
        audioLine.start()

//        val recorder = FFmpegFrameRecorder("out.aac", 2)
//        recorder.audioBitrate = 192_000
//        recorder.start()

        Thread({
            var prevSamples = FloatArray(irSamples.size / 2)
            val fft = FloatFFT_1D(irSamples.size.toLong())
            while (true) {
                val samples = readSamples(irSamples.size / 2) ?: break

                val input = prevSamples + samples
                prevSamples = samples
                fft.realForward(input)
                val dst = multipleComplex(input, irSamples)
                fft.realInverse(dst, true)
                val max = dst.max() ?: 1f
                for (i in 0 until dst.size) {
                    dst[i] /= max
                }
                Platform.runLater { drawWave(dst, srcCanvas) }
                val buf = ByteBuffer.allocate(dst.size).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until dst.size / 2) {
                    buf.putShort((dst[i] * Short.MAX_VALUE).toShort())
                }
                buf.position(0)
                val arr = buf.array()
                audioLine.write(arr, 0, arr.size / 4 * 4)
                println("write ${arr.max()}")
            }

            println("done")
        }).start()
    }

    var tmpBuffer: FloatBuffer? = null
    private fun readSamples(size: Int): FloatArray? {
        val result = FloatArray(size)
        var read = 0
        while (read < size) {
            if (tmpBuffer == null || tmpBuffer?.remaining() == 0)
                tmpBuffer = srcGrabber?.grabSamples()?.samples?.get(0) as? FloatBuffer ?: break

            val toRead = Math.min(tmpBuffer?.remaining() ?: 0, size - read)
            tmpBuffer?.get(result, read, toRead)
            read += toRead
        }
        return if (read > 0) result else null
    }

    private fun multipleComplex(src1: FloatArray, src2: FloatArray): FloatArray {
        if (src1.size != src2.size) throw Exception("長さの違う配列同士は乗算できません")

        val result = FloatArray(src1.size)

        for (i in 0 until result.size / 2) {
            result[i * 2] = src1[i * 2] * src2[i * 2] - src1[i * 2 + 1] * src2[i * 2 + 1]
            result[i * 2 + 1] = src1[i * 2] * src2[i * 2 + 1] + src2[i * 2] * src1[i * 2 + 1]
        }


        return result
    }
}