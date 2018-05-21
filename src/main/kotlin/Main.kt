import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.stage.Stage

class Main  : Application() {
    @Throws(Exception::class)
    override fun start(primaryStage: Stage) {
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            error.printStackTrace()
        }
        primaryStage.scene = Scene(FXMLLoader.load<Parent>(ClassLoader.getSystemResource("main.fxml")))
        primaryStage.setOnCloseRequest { System.exit(0) }
        primaryStage.show()
    }

    companion object {
        @JvmStatic
        fun main(args : Array<String>){
            Application.launch(Main::class.java, *args)
        }
    }
}