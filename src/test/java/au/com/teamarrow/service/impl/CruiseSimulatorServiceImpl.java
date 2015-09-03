package au.com.teamarrow.service.impl;

import java.text.NumberFormat;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import au.com.teamarrow.canbus.model.CanPacket;
import au.com.teamarrow.service.CruiseSimulatorService;
import au.com.teamarrow.utils.test.CarTestUtils;


@Service("cruiseSimulatorService")
@Transactional
public class CruiseSimulatorServiceImpl implements CruiseSimulatorService {
    
    private static final Logger LOG = LoggerFactory.getLogger(CanbusServiceImpl.class);
          
    @Autowired
    private CarTestUtils carTestUtils;
    
    private static int markerHertz = 10;
    private static int velocityHertz = 5;
    private static int variationHertz = 5;
    private static int logHertz = 10;
    private static int setpointScaler = 290;
    private static double crr = 0.001;
    private static double cd = 0.117;
    private static int mass = 310;
    private static double frontalArea = 0.91;
    private static double airDensity = 1.225;
    private static double velocityVariationPercent = 0.01;
    
	private double cruiseTargetRpm = 0;
    private double cruiseStatus = 0;
    private double integralFactor = 0;
    private double proportionalFactor = 0;
    private double combinedError = 0;
    private double setpoint = 0;
    
    private double currentVelocity = 0;
    private long clock = 1;
    
    
    public int getVariationHertz() {
		return variationHertz;
	}


	public void setVariationHertz(int variationHertz) {
		CruiseSimulatorServiceImpl.variationHertz = variationHertz;
	}


	public double getVelocityVariationPercent() {
		return velocityVariationPercent;
	}


	public void setVelocityVariationPercent(double velocityVariationPercent) {
		CruiseSimulatorServiceImpl.velocityVariationPercent = velocityVariationPercent;
	}
    
        
    
    private double calcNewVelocity(double setpoint) {
    	    	
    	// Calculate the amount of force being generated by the wheel, basically this is a torque calculation, calculated in newtons
    	// force = setpoint * determined scaler
    	double setpointForce = setpoint * setpointScaler;
    	
    	// Calculate the rolling resistance forces on the car, force in newtons
    	// force = mass * gravity * crr;
    	// If we are not moving set the crrForce to 0 as it causes issues later on
    	double crrForce = mass * 9.8 * crr;        	
    	if ( currentVelocity == 0 ) crrForce = 0;
    	
    	// Calculate the aero forces acting on the car, force in newtons
    	// 1/2 * frontal area * cd * air density * velocity squared
    	double cdForce = 0.5 * frontalArea * cd * airDensity * currentVelocity * currentVelocity;
    	
    	// Now we calculate the total forces acting on the car, in a simple force in / force out equation
    	// total force = setpointForce - (crrForce + cdForce)
    	double totalForce = setpointForce - (crrForce + cdForce);

    	// We now have a force in newtons acting on the car, if we assume that the car is going in a straight line
    	// we can now use this force to determine an acceleration, with the simple formula
    	// acceleration = force / mass
    	double acceleration = totalForce / mass;
    	
    	// This function is called markerHertz times a second so to figure on the change in velocity
    	// delta velocity = acceleration * time;
    	double deltaVelocity = acceleration * ((double)1 / (double)markerHertz);    	    	
    	
    	// System.out.println("V:" + currentVelocity + "  SP:" + setpoint + "  SPF:" + setpointForce + "  crrForce:" + crrForce + "  cdForce:" + cdForce + "  totalForce:" + totalForce);
    	
    	// return the new velocity
    	return (currentVelocity + deltaVelocity);
    	    	    	
    }
    
    
    @ServiceActivator
    public void processCanPacketMessage(Message<CanPacket> message) {
    	              
        // High is two
    	// Low is one

    	NumberFormat percentFormat = NumberFormat.getPercentInstance();
    	percentFormat.setMaximumFractionDigits(1);
    	
    	NumberFormat velocityFormat = NumberFormat.getNumberInstance();
    	velocityFormat.setMaximumFractionDigits(2);
            	
        
        
        CanPacket cp = message.getPayload();     
        
        
                
        // If we got a setpoint (0x501)
        if ( cp.getIdBase10() == 1281 ) {
        	
        	// Increment the clock
        	clock++;
        	
        	setpoint = cp.getDataSegmentTwo();
        	                	        	
        	// Second float should be the setpoint
        	currentVelocity = calcNewVelocity(setpoint);
        	        
        }


    	// Variation Hertz
    	if ( variationHertz != 0 && clock % (markerHertz / variationHertz) == 0 ) {
    		
    	    Random rand = new Random();

    	    // nextInt is normally exclusive of the top value,
    	    // so add 1 to make it inclusive
    	    double randomNumPercent = (rand.nextInt((100 - -100) + 1) + -100) / (double)100;
    	    double percentToVary = velocityVariationPercent * randomNumPercent;
    	    double velocityToVary = currentVelocity * percentToVary;
    		
    	    currentVelocity = currentVelocity + velocityToVary;
    		
    	}

        
    	// x403 Hertz
    	if (  velocityHertz != 0 && clock % (markerHertz / velocityHertz) == 0 ) {        		        		
    		
   	      	CanPacket canPacket = new CanPacket(1027, (float)currentVelocity, (float)CarTestUtils.kphToRPM(currentVelocity * 3.6));        	        	
    	    carTestUtils.sendCan(canPacket);
    	}
    	
    	// Log Hertz
    	if ( logHertz != 0 && clock % (markerHertz / logHertz) == 0 ) {
    		System.out.println(percentFormat.format(setpoint) + "," + velocityFormat.format(currentVelocity) + "," + velocityFormat.format(CarTestUtils.kphToRPM(currentVelocity * 3.6)) + "," + cruiseTargetRpm + "," + proportionalFactor + "," + integralFactor + "," + combinedError + "," + cruiseStatus);	        		        	
    	}
        
        
      
        
        
        // If we got a cruise debug (0x507)
        if ( cp.getIdBase10() == 1287) {        	
        	proportionalFactor = cp.getDataSegmentTwoInt();        	
        	integralFactor = cp.getDataSegmentOneInt();        	
        }
        
        // If we got a cruise debug (0x508)
        if ( cp.getIdBase10() == 1288) {  
        	combinedError = cp.getDataSegmentOneInt();
        	cruiseTargetRpm = cp.getDataSegmentTwoShortInt();
        	cruiseStatus = cp.getDataSegmentTwoByteSevenInt();        	        	        
        }
                         
    }
}
