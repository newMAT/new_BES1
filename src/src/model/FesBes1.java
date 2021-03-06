package model;

import java.util.*;
import java.util.Map.Entry;

import javax.persistence.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import mail.ISendActivationMail;
import mat.*;

public class FesBes1 implements IFesBes1 {
	static final int MIN_PER_HOUR=60;
	
	//@PersistenceContext(unitName = "springHibernate", type = PersistenceContextType.EXTENDED)
	@PersistenceContext(type=PersistenceContextType.EXTENDED)
	EntityManager em;

	@Autowired
	ApplicationContext ctx;

	@Autowired
	IBackConnector iBackCon;

	@Override
	@Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW)
	public int setProfile(Person person) {
		int result = Response.UNKNOWN;
		if (person != null) {
			PersonEntity prsEntity = em.find(PersonEntity.class, person.getEmail());
			if (prsEntity == null) {
				prsEntity = new PersonEntity(person);
				prsEntity.setHashCode(UUID.randomUUID().toString());//create unique confirmation code for person
				em.persist(prsEntity);  
				
				launchActivation(prsEntity);//launch activate mechanism
				result = Response.OK;
			} else {	//currentPE exists, checking activation status
				if (prsEntity.isActive() == false)
					result = Response.PROFILE_EXISTS_INACTIVE;
				else
					result = Response.PROFILE_EXISTS_ACTIVE;
			}
		}
		return result;
	}

	@Override
	public int matLogin(String userName, String password) {
		PersonEntity pe = em.find(PersonEntity.class, userName); // looking for person in database by email
		int result;
		 if (pe == null)							//person not found
			 result = Response.NO_REGISTRATION;
		 else
			 if (pe.isActive() == false)			//person found, but not active
				 result = Response.IN_ACTIVE;
			 else									//person found and active
				 if ((pe.getPassword()).equals(password)==true)	//password correct
					 result = Response.OK;
				 else								//password not correct
					 result = Response.NO_PASSWORD_MATCHING;
		return result; 
	}
	
	@Override
	public int ifEmailExistsInDB(String userName){
		int result = Response.NO_REGISTRATION;
		PersonEntity pe = em.find(PersonEntity.class, userName); // looking for person in database by email
		if (pe != null){						//person not found
			if (pe.isActive() == false)			//person found, but not active
				 result = Response.IN_ACTIVE;
			else result=Response.OK;
		} 
		return result;
	}

	private Map<Date, LinkedList<Integer>> slotsBoolToMap (	ArrayList<Boolean> slots, MattData data) {
		Map<Date, LinkedList<Integer> > result = new TreeMap<Date, LinkedList<Integer> >();
		if(slots != null && !slots.isEmpty() && data!= null){
			int size = slots.size();
			int numberOfSlotsPerDay=data.getEndHour()-data.getStartHour();
			HashMap<Integer, Date> dates=new HashMap<Integer, Date>();
			Calendar calendar = new GregorianCalendar();
			if (numberOfSlotsPerDay > 0){
				for (int i=0; i<size; i++){
					//System.out.println(slots.get(i).booleanValue());
					if(!slots.get(i).booleanValue()){ //returns false if slot value is true i.e. busy.
						int dayNumber = i/numberOfSlotsPerDay; //because division returns the number of past days
					    if(!dates.containsKey(dayNumber)){
					    	calendar.setTime(data.getStartDate());
							calendar.add(Calendar.DATE, dayNumber);
							dates.put(dayNumber, calendar.getTime());
					    }
						LinkedList<Integer> slotNums = result.get(calendar.getTime());
						if (slotNums != null){
							slotNums.add(i);
							result.replace(calendar.getTime(), slotNums); //change "replace" to "put", as replace appeared only since 1.8
						}
						else {
							slotNums = new LinkedList<Integer>();
							slotNums.add(i);
							result.put(calendar.getTime(), slotNums);
						}				
					}
				}
			}
		}
		return result;
	}

	@Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW)
	@Override
	public int saveMatt(Matt mattNew, String userName) {
		int mattId = -1;
		MattInfoEntity mattInfoEntity = null;
		if (mattNew != null && userName != null) {
			//determine person_id by userName
			PersonEntity prs = em.find(PersonEntity.class, userName);
			if(prs == null) return -1;
			if(mattNew.getData()!=null) 
				mattId = mattNew.getData().getMattId();
			if(mattId>0) 
				mattInfoEntity = em.find(MattInfoEntity.class, mattId);
			if (mattInfoEntity != null){ 
				//if true - the Matt is exists in DB and we should perform updating of existing Matt.
				//otherwise (if false) - saving New Matt
				//check if slots were changed
				if(!getSlotsFromDB(mattInfoEntity).equals(mattNew.getSlots())){
					//deleting existing slots from DB
					for(BusySlotEntity slot: mattInfoEntity.getSlots())
						em.remove(slot);
					//saving new slots
					mattInfoEntity.setSlots(createListOfMattSlots(mattNew, mattInfoEntity));
				}
				mattInfoEntity.setName(mattNew.getData().getName());
				mattInfoEntity.setEndHour(mattNew.getData().getEndHour());
				mattInfoEntity.setnDays(mattNew.getData().getnDays());
				mattInfoEntity.setPassword(mattNew.getData().getPassword());
				mattInfoEntity.setStartDate(mattNew.getData().getStartDate());
				mattInfoEntity.setStartHour(mattNew.getData().getStartHour());
				mattInfoEntity.setTimeSlot(mattNew.getData().getTimeSlot());
				//set snCalendars 
				LinkedList<SnCalendarsEntity> snCalendars = new LinkedList<>();
				//checking if SN [] to download is not null
				String [] snDownload = mattNew.getData().getDownloadSN();
				if(snDownload != null && snDownload[0] != null){
					//passing through array of SNs and getting all calendar names for each SN
					for(int i=0; i<snDownload.length; i++){
						List<String> downloadCalendarName = mattNew.getData().getDownloadCalendars(snDownload[i]);
						//getting SocialNetworkEntity instance from DB
						SocialNetworkEntity snEntity = getSNInstanceFromDB(snDownload[i]);
						//creating separate SnCalendarsEntity for each Calendar.
						//Add the entity to Calendars to Download list
						for(String calendName: downloadCalendarName)
							snCalendars.add(new SnCalendarsEntity(mattInfoEntity, snEntity, 
									SnCalendarsEntity.DOWNLOAD, calendName));
					}
					
				}
				//checking if SN [] to upload is not null
				String [] snUpload = mattNew.getData().getUploadSN();
				if (snUpload != null && snUpload[0] != null){
					//passing through array of SNs and getting all calendar names for each SN
					for(int i=0; i<snUpload.length; i++){
						List<String> uploadCalendarName = mattNew.getData().getUploadCalendars(snUpload[i]);
						//getting SocialNetworkEntity instance from DB
						SocialNetworkEntity snEntity = getSNInstanceFromDB(snUpload[i]);
						//creating separate SnCalendarsEntity for each Calendar.
						//Add the entity to Calendars to Download list
						for(String calendName: uploadCalendarName)
							snCalendars.add(new SnCalendarsEntity(mattInfoEntity, snEntity, 
									SnCalendarsEntity.UPLOAD, calendName));
					}
				}
				//saving snCalendars
				if(mattInfoEntity.getSncalendars() != null && !snCalendars.equals(mattInfoEntity.getSncalendars())){ //if snCalendars list was changed 
					for(SnCalendarsEntity snCal : mattInfoEntity.getSncalendars()) //deleting old entities from DB
						em.remove(snCal);
					mattInfoEntity.setSncalendars(snCalendars); //setting new snCalendar list
				}
				
			}
			else {
		//saving to DB if newMatt name unique for the user
				//creating MattInfoEntity
				MattData data = mattNew.getData();
				MattInfoEntity mattInfo = new MattInfoEntity(data.getName(), data.getPassword(), 
						data.getnDays(), data.getStartDate(), data.getStartHour(), 
						data.getEndHour(), data.getTimeSlot(), prs);
				//creating List<MattSlots> to save slots to DB
				mattInfo.setSlots(createListOfMattSlots(mattNew, mattInfo));
				em.persist(mattInfo);
				mattId=mattInfo.getMatt_id();
				//set snCalendars 
				LinkedList<SnCalendarsEntity> snCalendars = new LinkedList<>();
				//checking if SN [] to download is not null
				String [] snDownload = mattNew.getData().getDownloadSN();
				if(snDownload != null && snDownload[0] != null){
					//passing through array of SNs and getting all calendar names for each SN
					for(int i=0; i<snDownload.length; i++){
						List<String> downloadCalendarName = mattNew.getData().getDownloadCalendars(snDownload[i]);
						//getting SocialNetworkEntity instance from DB
						SocialNetworkEntity snEntity = getSNInstanceFromDB(snDownload[i]);
						//creating separate SnCalendarsEntity for each Calendar.
						//Add the entity to Calendars to Download list
						for(String calendName: downloadCalendarName)
							snCalendars.add(new SnCalendarsEntity(mattInfo, snEntity, 
									SnCalendarsEntity.DOWNLOAD, calendName));
					}
				}
				//checking if SN [] to upload is not null
				String [] snUpload = mattNew.getData().getUploadSN();
				if (snUpload != null && snUpload[0] != null){
					//passing through array of SNs and getting all calendar names for each SN
					for(int i=0; i<snUpload.length; i++){
						List<String> uploadCalendarName = mattNew.getData().getUploadCalendars(snUpload[i]);
						//getting SocialNetworkEntity instance from DB
						SocialNetworkEntity snEntity = getSNInstanceFromDB(snUpload[i]);
						//creating separate SnCalendarsEntity for each Calendar.
						//Add the entity to Calendars to Download list
						for(String calendName: uploadCalendarName)
							snCalendars.add(new SnCalendarsEntity(mattInfo, snEntity, 
								SnCalendarsEntity.UPLOAD, calendName));
					}
				}
				mattInfo.setSncalendars(snCalendars); //setting new snCalendar list
			}
		}
		return mattId;
	}
	
	@Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW)
	@Override
	public int saveGuestsMatt(int mattId, Matt newGuestMatt, String guestName) {
		int result=0;
		if (mattId > 0 && newGuestMatt != null && guestName != null) {
			MattInfoEntity mattInfoEntity = em.find(MattInfoEntity.class, mattId);
			Query queryNot = em.createQuery("update NotificationEntity n set n.checked_fl = :guestMattId where n.mattInfo = :mattInfo and n.guest_email = :guestEmail");
			if(mattInfoEntity!=null){
				result = saveMatt(newGuestMatt, mattInfoEntity.getPersonEntity().getEmail());
				if(result>0){
					queryNot.setParameter("guestMattId", result);
					queryNot.setParameter("mattInfo", mattInfoEntity);
					queryNot.setParameter("guestEmail", guestName);
					queryNot.executeUpdate();
				}
			}
		}
		return result;
	}
	
	//getting SocialNetworkEntity instance from DB
	private SocialNetworkEntity getSNInstanceFromDB(String snName) {
		Query query = em.createQuery("SELECT sn FROM SocialNetworkEntity sn where sn.name= :snName");
		query.setParameter("snName", snName);
		return (SocialNetworkEntity) query.getSingleResult();
	}

	//creating List<BusySlotEntity> to save slots to DB
	private List<BusySlotEntity> createListOfMattSlots(Matt mattNew, MattInfoEntity mattInfoEntity) {
		Map<Date, LinkedList<Integer> > boolSlots_toSlotNums = slotsBoolToMap(mattNew.getSlots(), mattNew.getData());
		List<BusySlotEntity> busySlots = new ArrayList<BusySlotEntity>();
		BusySlotEntity tmpSE;
		if (!boolSlots_toSlotNums.isEmpty()){ //Map isEmpty if no user selection
			for(Map.Entry<Date, LinkedList<Integer>> entry: boolSlots_toSlotNums.entrySet()){
				LinkedList<Integer> slotsByDate = entry.getValue();
			//creating list of separate MattSlots 
				for(int slot_num : slotsByDate){
					tmpSE = new BusySlotEntity(entry.getKey(), slot_num, mattInfoEntity);
					if(mattNew.getSlotsInfo()!=null && mattNew.getSlotsInfo().containsKey(slot_num))
						tmpSE.setSlotInfo(new SlotInfoEntity(tmpSE, mattNew.getSlotsInfo().get(slot_num)));
					busySlots.add(tmpSE);
				}	
			}
		}
		return busySlots;
	}

	@Override
	public void updateMatCalendarInSN(String userName, String snName) { //updating Mat Calendars in SN
		if (userName != null && snName != null){
			PersonEntity prs = em.find(PersonEntity.class, userName);
		//building list of actual MATTs	for current user
		// 1 - creating Query to select all actual for today Matt names for this user
		// using native SQL for mySQL server, because JPQL currently doesn't support required DATE operations
			/*Query query = em.createNativeQuery("select * from test.MattsInfo where person_id=" + prs.getId() +
					" and date_add(startDate, interval nDays day) > curdate()", MattInfoEntity.class);
			List<MattInfoEntity> mattEntities = query.getResultList();*/
		//alternative flow
			//1. select all MATTs for the user
			Query query = em.createQuery("select m from MattInfoEntity m where m.personEntity= :person");
			query.setParameter("person", prs);
			List<MattInfoEntity> allMattEntities = query.getResultList();
			List<Matt> actualUserMatts = new LinkedList<Matt>();
			if(allMattEntities != null && !allMattEntities.isEmpty()){
				Calendar today = new GregorianCalendar();
				today = Calendar.getInstance();
				for(MattInfoEntity entity:allMattEntities){
					today.add(Calendar.DATE, -entity.getnDays());
					if (entity.getStartDate().compareTo(today.getTime()) >= 0)
						actualUserMatts.add(getMattFromMattEntity(entity, userName));
				}
			}
		
		}
	}

	private Matt getMattFromMattEntity(MattInfoEntity entity, String username) {
		Matt matt = new Matt();
		MattData mattData = new MattData(entity.getName(), entity.getnDays(), entity.getStartDate(), 
					entity.getStartHour(), entity.getEndHour(), entity.getTimeSlot(), entity.getPassword());
		mattData.setMattId(entity.getMatt_id());							//additional mattId to mattDate
		//populating HashMap<String, List<String>[]> sncalendars
		List<SnCalendarsEntity> snCalendarsEntities= entity.getSncalendars(); //getting list of SnCalendars
		if(snCalendarsEntities != null && !snCalendarsEntities.isEmpty()){
			HashMap<String, List<String>[]> snCalendars = new HashMap<>(); //creating HashMap
			for(SnCalendarsEntity calend: snCalendarsEntities){
				//getting List of calendars for current SN
				List<String>[] calendarNames  = snCalendars.get(calend.getSocial_net().getName()); 
				if(snCalendars.containsKey(calend.getSocial_net().getName())){ //updating value for this key
					if(calend.getUpload_download_fl() == SnCalendarsEntity.UPLOAD){
						if (calendarNames[0] == null) //if no Calendar to Upload exists
							calendarNames[0] = new ArrayList<String>(); //creating upload list
						calendarNames[0].add(calend.getCalendarName());
						snCalendars.replace(calend.getSocial_net().getName(), calendarNames);
					}
					else if(calend.getUpload_download_fl() == SnCalendarsEntity.DOWNLOAD){
						if (calendarNames[1] == null) //if no Calendar to Download exists
							calendarNames[1] = new ArrayList<String>(); //creating Download list
						calendarNames[1].add(calend.getCalendarName());
						snCalendars.replace(calend.getSocial_net().getName(), calendarNames);
					}
				}
				else { //adding new key
					List<String>[] newCalendarNames = new List[2]; //creating array of Lists
					if(calend.getUpload_download_fl() == SnCalendarsEntity.UPLOAD){
						newCalendarNames[0] = new ArrayList<String>(); //creating upload list
						newCalendarNames[0].add(calend.getCalendarName());
						snCalendars.put(calend.getSocial_net().getName(), newCalendarNames);
					}
					else if (calend.getUpload_download_fl() == SnCalendarsEntity.DOWNLOAD){
						newCalendarNames[1] = new ArrayList<String>();
						newCalendarNames[1].add(calend.getCalendarName());
						snCalendars.put(calend.getSocial_net().getName(), newCalendarNames);
					}
				}
			}
			//setting snCalendars to MattData
			mattData.setSNCalendars(snCalendars);		
		}
			matt.setData(mattData);
			matt.setSlots(getSlotsFromDB(entity)); //getting slots from DB
			//if the Matt isn't synchronized with SN => returning existing Matt, otherwise - invoking getSlots() from iBackCon.
		return (entity.getSncalendars() != null && !entity.getSncalendars().isEmpty()) ?
				   iBackCon.getSlots(entity.getPersonEntity().getEmail(), matt) : matt;
	}
		
	
	@Override
	@Transactional
	//functions call sequence: getMatt() -> getMattFromMattEntity() -> getSlotsFromDB()
	public Matt getMatt(int matt_id) {
		MattInfoEntity entity = em.find(MattInfoEntity.class, matt_id); //looking for mattEntity by ID
		//getting userName from the entity and invoking getMattFromMattEntity() if MattEntity was found
		//returning null if MattEntity doesn't exists
		return (entity != null) ? 
				getMattFromMattEntity(entity, entity.getPersonEntity().getEmail()) : null; 
	}

	private ArrayList<Boolean> getSlotsFromDB(MattInfoEntity mattEntity) {
	//determining number of slots and creating Boolean list with all slots marked as false.
		int numberOfSlotsPerDay=mattEntity.getEndHour()-mattEntity.getStartHour();
		int slotsNumber = numberOfSlotsPerDay * mattEntity.getnDays() * FesBes1.MIN_PER_HOUR/mattEntity.getTimeSlot();
		ArrayList<Boolean> slotsFromDB = new ArrayList<Boolean>(Collections.nCopies(slotsNumber, true));
	//taking busy slot numbers from DB and changing ArrayList values (setting to true) by the index
		List<BusySlotEntity> mattSlots = mattEntity.getSlots();
		if (mattSlots != null)
			for (BusySlotEntity mattSlot : mattSlots)
				//check if we should put true/false in the list
				slotsFromDB.set(mattSlot.getSlot_number(), false);
		return slotsFromDB;
	}

	@Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW)
	@Override
	public boolean removeMatt(int matt_id) {
		boolean result=false;
		MattInfoEntity deleteRow = em.find(MattInfoEntity.class, matt_id);
		if (deleteRow != null){
			em.remove(deleteRow);
			result=true;
		}		
		return result;
	}

	@Override
	public HashMap<Integer, String> getMattNames(String userName) {
		HashMap<Integer, String> result=new HashMap<Integer, String>(); 
		PersonEntity prs = em.find(PersonEntity.class, userName);
		String str = "Select m from MattInfoEntity m where m.personEntity = :user and m.name != null";
		Query query = em.createQuery(str); //sending query
		query.setParameter("user", prs);
		List<MattInfoEntity> listOfMats = query.getResultList(); //getting result list
		for(MattInfoEntity entity:listOfMats)
		 	result.put(entity.getMatt_id(),entity.getName());
		return result;
	}

	@Override
	public Person getProfile(String userName) {
		if(userName == null) return null;
		PersonEntity pe = em.find(PersonEntity.class, userName);
		return pe==null ? null: new Person(pe.getName(), pe.getFamily(), pe.getEmail(), pe.getPassword(), pe.getTimeZone());
	}

	@Override
	@Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW)
	public void setActive(String userName, String hashcode) {
		PersonEntity pe = em.find(PersonEntity.class, userName);
		if (pe != null && pe.getHashCode().equals(hashcode))
			pe.setActive(true);
	}

	@Override
	@Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW)
	public int updateProfile(Person person) {
		int result = Response.UNKNOWN;
		if (person != null) {
			PersonEntity pe = em.find(PersonEntity.class, person.getEmail());
			result = Response.NO_REGISTRATION;
			if (pe != null) {
				pe.setName(person.getName());
				pe.setFamily(person.getFamilyName());
				pe.setPassword(person.getPassword());
				pe.setTimeZone(person.getTimeZone());	
				result = Response.OK;
			}
		}
		return result;
	}
	
	
	@Transactional(readOnly=false, propagation=Propagation.REQUIRES_NEW)
	public boolean deletePerson(String userName){
		boolean result = false;
		PersonEntity prs = em.find(PersonEntity.class, userName);
		if (prs != null){
			em.remove(prs);
			result=true;
		}
		return result;
	}
	
	private void launchActivation(PersonEntity pe) {
		ISendActivationMail sender = (ISendActivationMail) ctx.getBean("sender");
		sender.sendMail(pe);
	}

	@Override
	public HashMap<String, String> getCheckedGuestsMatts(int mattId) {
		Integer gMattId = null;
		Matt tmpMatt = null;
		HashMap<String, String> chGuestsMatts = new HashMap<String, String>();
		if(mattId > 0){
		    Query query = em.createQuery("select n from NotificationEntity n where n.mattInfo= :mattInfo");
			MattInfoEntity mattInfo = em.find(MattInfoEntity.class, mattId);
			if (mattInfo!=null){
			    query.setParameter("mattInfo", mattInfo);
			    List<NotificationEntity> noteList = query.getResultList();
			    if(noteList!=null)
			    	for(NotificationEntity ne: noteList){
			    		gMattId = ne.getGuestMattId();
			    		if(gMattId!=null && gMattId > 0){
			    			tmpMatt = getMatt(gMattId);
			    			if(tmpMatt!=null)
			    				chGuestsMatts.put(ne.guest_email, tmpMatt.mattToBrowser());
			    		}
			    	}
			}
		}
		return chGuestsMatts;
	}

	@Override
	public List<Notification> getNotifications(String guestName) {
	    List<NotificationEntity> noteList=null;
	    List<Notification> rt = new LinkedList<>();
	    Query query = em.createQuery("select n from NotificationEntity n where n.guest_email= :guestName and n.guestMattId = 0");
	    query.setParameter("guestName", guestName);
	    noteList = query.getResultList();
	    if (noteList != null && !noteList.isEmpty())
		    for(NotificationEntity ne:noteList){
		    	Notification notif = new Notification();
		    	notif.mattId = ne.getMattInfo().getMatt_id();
		    	notif.mattName = ne.getMattInfo().getName();
		    	PersonEntity person=ne.getMattInfo().getPersonEntity();
		    	if(person != null){
		    		notif.nameOfUser=person.getName();
		    		notif.userEmail=person.getEmail();
		    	}
		    	rt.add(notif);
		    }
	    return rt;
	}
	
	@Override
	@Transactional(readOnly=false, propagation=Propagation.REQUIRES_NEW)
	public boolean setGuests(int matt_id, String [] guestEmails) {
		Boolean result=false;
		MattInfoEntity mattInfo = em.find(MattInfoEntity.class, matt_id);
		NotificationEntity notification = null;
		List<NotificationEntity> lNot = null;
		if (mattInfo!= null){
			result=true;
			for(int i=0;i<guestEmails.length;i++){
				lNot = mattInfo.getNotifications();
				if(lNot!=null)
					for(NotificationEntity not: lNot)
						if(not.guest_email.equals(guestEmails[i])){
							if(not.guestMattId!=0){
								removeMatt(not.guestMattId);
								not.guestMattId = 0;
							}
							notification = not;
							break;
						}
					if(notification == null)	
						notification = new NotificationEntity(mattInfo, guestEmails[i]);
					em.persist(notification);
					notification = null;
					lNot = null;
			}
			iBackCon.sendInvitation(mattInfo.getPersonEntity().getEmail(), 
				mattInfo.getPersonEntity().getName(), mattInfo.getName(), guestEmails);
		}
		return result;
	}

	@Override
	public String[] getGuests(int mattId) {
		if(mattId > 0){
			Query query = em.createQuery("select n from NotificationEntity n where n.mattInfo = :mattInfo");
			MattInfoEntity mattInfo = em.find(MattInfoEntity.class, mattId);
			query.setParameter("mattInfo", mattInfo);
			List<NotificationEntity> nf = query.getResultList();
			if(nf!=null){
				String[] result = new String[nf.size()];
				int ind = 0;
				for(NotificationEntity ne: nf)
					result[ind++] = ne.getGuest_email();
					return result;
			}
		}
		return null;
	}

	@Override
	public Matt updateInvitationMatt(int matt_id, String username, HashMap<String, List<String>> sncalendars) {
		Matt result=getMatt(matt_id);// create new Matt obtained by id		
		if(sncalendars!=null){
			MattData resultdata=result.getData();
			resultdata.setSNCalendars(new HashMap<String, List<String>[]>());
			Set<Entry<String,List<String>>> start=sncalendars.entrySet();//create start point for mooving through HashMap
			for(Entry<String,List<String>>entry:start)	//go around snCalendars 
				resultdata.setDownloadCalendars(entry.getKey(), entry.getValue());//reset result HashMap from shcalendars
			result=iBackCon.getSlots(username, result);	//update result
		}
		return result;
	}
}