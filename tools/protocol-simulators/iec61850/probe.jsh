import com.beanit.iec61850bean.*;
import java.net.*;
var sap = new ClientSap();
sap.setResponseTimeout(5000);
var listener = new ClientEventListener() { public void newReport(Report report) {} public void associationClosed(java.io.IOException error) {} };
var association = sap.associate(InetAddress.getByName("127.0.0.1"), 11030, null, listener);
if (!association.isOpen()) System.exit(1);
System.out.println("MMS_ASSOCIATED=true");
var model = association.retrieveModel();
System.out.println("MMS_MODEL_LDS=" + model.getChildren().size());
for (int i = 1; i <= 8; i++) {
    var path = "REFIEDLD0/GGIO1.AnIn" + i;
    var value = (BdaFloat32) model.findModelNode(path + ".mag.f", Fc.MX);
    var quality = (BdaQuality) model.findModelNode(path + ".q", Fc.MX);
    if (value == null || quality == null) System.exit(2);
    association.getDataValues(value);
    association.getDataValues(quality);
    if (value.getFloat() == null || !Float.isFinite(value.getFloat()) || !"GOOD".equals(quality.getValidity().toString())) System.exit(3);
    System.out.println("MMS_POINT=" + path + ".mag.f VALUE=" + value.getFloat() + " QUALITY=" + quality.getValidity());
}
association.close();
/exit
