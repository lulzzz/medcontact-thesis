package com.medcontact.controller.services;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.medcontact.data.model.domain.Doctor;
import com.medcontact.data.model.domain.FileEntry;
import com.medcontact.data.model.domain.Patient;
import com.medcontact.data.model.domain.Reservation;
import com.medcontact.data.model.domain.SharedFile;
import com.medcontact.data.model.dto.BasicReservationData;
import com.medcontact.data.model.dto.BasicSharedFileData;
import com.medcontact.data.model.dto.BasicUserData;
import com.medcontact.data.model.dto.ConnectionData;
import com.medcontact.data.model.dto.SharedFileDetails;
import com.medcontact.data.model.dto.UserFilename;
import com.medcontact.data.model.enums.ReservationState;
import com.medcontact.data.repository.FileRepository;
import com.medcontact.data.repository.PatientRepository;
import com.medcontact.data.repository.ReservationRepository;
import com.medcontact.data.repository.SharedFileRepository;
import com.medcontact.exception.ItemNotFoundException;
import com.medcontact.exception.NotMatchedPasswordException;
import com.medcontact.exception.ReservationNotFoundException;
import com.medcontact.exception.ReservationTakenException;
import com.medcontact.exception.UnauthorizedUserException;
import com.medcontact.exception.UserNotFoundException;
import com.medcontact.security.config.EntitlementValidator;

import lombok.Getter;
import lombok.Setter;

@Service
public class PatientService {
    private Logger logger = Logger.getLogger(this.getClass().getName());

    private Cache<UserFilename, FileEntry> cachedFileEntries = CacheBuilder.newBuilder()
            .expireAfterWrite(2, TimeUnit.MINUTES)
            .concurrencyLevel(4)
            .build();

    @Getter
    @Setter
    @Value("${general.files-path-root}")
    private String patientFilesPathRoot;

    @Getter
    @Setter
    @Value("${backend.host}")
    private String host;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private SharedFileRepository sharedFileRepository;

    @Autowired
    private EntitlementValidator entitlementValidator;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${webrtc.turn.api-endpoint}")
    private String turnApiEndpoint;

    @Value("${webrtc.turn.ident}")
    private String turnIdent;

    @Value("${webrtc.turn.domain}")
    private String turnDomain;

    @Value("${webrtc.turn.application}")
    private String turnApplicationName;

    @Value("${webrtc.turn.secret}")
    private String turnSecret;

    public ResponseEntity<ConnectionData> getConnectionData(Long patientId, Long reservationId) {
        ConnectionData body = null;
        HttpStatus status = HttpStatus.BAD_REQUEST;
        Patient patient = patientRepository.findOne(patientId);

        if (!patientId.equals(patient.getId())) {
            status = HttpStatus.BAD_REQUEST;

        } else {
            Reservation reservation = reservationRepository.findOne(reservationId);
            LocalDateTime currentDateTime = LocalDateTime.now();

            if (reservation == null) {
                status = HttpStatus.BAD_REQUEST;
                logger.warning("[CONNECTION DATA]: No reservation with specified ID found");

            } else {
                Doctor doctor = reservation.getDoctor();

                if (!patientId.equals(reservation.getPatient().getId())) {
                    status = HttpStatus.BAD_REQUEST;
                    logger.warning("[CONNECTION DATA]: Invalid patient ID for the specified reservation");

                } else if (currentDateTime.isBefore(reservation.getStartDateTime())
                        || currentDateTime.isAfter(reservation.getEndDateTime())) {

                    status = HttpStatus.BAD_REQUEST;
                    logger.warning("[CONNECTION DATA]: Invalid date or time of the reservation");

                } else {
                    body = new ConnectionData();
                    body.setIceEndpoint(turnApiEndpoint + "ice");
                    body.setIdent(turnIdent);
                    body.setDomain(turnDomain);
                    body.setApplication(turnApplicationName);
                    body.setSecret(turnSecret);
                    body.setRoom(doctor.getRoomId());
                    status = HttpStatus.OK;
                    logger.info("[CONNECTION DATA]: Loaded data");
                }
            }
        }

        return new ResponseEntity<>(body, status);
    }

    public List<FileEntry> getFileEntries(Long patientId) throws UnauthorizedUserException {
        if (entitlementValidator.isEntitled(patientId, Patient.class)) {
            return patientRepository.findOne(patientId)
                    .getFileEntries();

        } else {
            return new ArrayList<>();
        }
    }

    public void getFile(Long patientId, Long fileId,
            HttpServletResponse response)
            		throws UnauthorizedUserException, IOException {

        if (entitlementValidator.isEntitled(patientId, Patient.class)) {
            FileEntry fileEntry = fileRepository.findOne(fileId);

            if (fileEntry != null) {
                response.setContentType(fileEntry.getContentType());
                response.setContentLengthLong(fileEntry.getContentLength());
                response.setHeader(
                        "Content-Disposition",
                        String.format("attachment; filename=\"%s\"",
                                fileEntry.getName()));

                try (OutputStream out = response.getOutputStream();) {
                    Files.copy(
                            Paths.get(fileEntry.getPath()), out);
                }
            }
        }
    }

    public void handleFileUpload(Long patientId, List<MultipartFile> files) throws IOException, UnauthorizedUserException, SQLException {

        if (entitlementValidator.isEntitled(patientId, Patient.class)) {

        	/* We have to load patient from the repository
             * because using principal object from the
			 * authentication context causes throwing a lazy
			 * loading exception */

            Patient patient = patientRepository.findOne(patientId);

            for (MultipartFile file : files) {

                String filePath = String.format(
                        "%s/%d/%s",
                        patientFilesPathRoot,
                        patientId,
                        file.getOriginalFilename());

                String fileUrl = String.format(
                        "%s/%s/%d/files/",
                        host,
                        patientFilesPathRoot,
                        patientId);

                List<FileEntry> foundEntries = fileRepository.findByFilenameAndOwnerId(
                        file.getOriginalFilename(), patientId);

				/* We use file entries cache because it's possible that
                 * file upload is divided into 2 or more subsequent method calls.
				 * Because of that the file entry repository might not be able to
				 * save the file entry quickly enough so that it can be
				 * found during the second call. */

                UserFilename soughtFileEntry = new UserFilename(patientId, file.getOriginalFilename());
                FileEntry cachedFileEntry = cachedFileEntries.getIfPresent(soughtFileEntry);

                FileEntry fileEntry = (foundEntries.size() > 0)
                        ? foundEntries.get(0)
                        : (cachedFileEntry != null)
                        ? cachedFileEntry
                        : new FileEntry();

                fileEntry.setName(file.getOriginalFilename());
                fileEntry.setUploadTime(
                        Timestamp.valueOf(
                                LocalDateTime.now()));
                fileEntry.setFileOwner(patient);
                fileEntry.setContentType(file.getContentType());
                fileEntry.setContentLength(file.getSize());
                fileEntry.setPath(Paths.get(filePath).toAbsolutePath().toString());
                patient.getFileEntries().add(fileEntry);

                fileEntry.setUrl(fileUrl + fileEntry.getId());
                fileEntry = fileRepository.save(fileEntry);

				/* If the entry is saved for the first time
                 * we need to update its URL */

                if (foundEntries.size() == 0) {
                    fileEntry.setUrl(fileUrl + fileEntry.getId());
                    fileRepository.save(fileEntry);
                }

                cachedFileEntries.put(soughtFileEntry, fileEntry);

                File fileToWrite = Paths.get(filePath).toAbsolutePath().toFile();
                fileToWrite.getParentFile().mkdirs();
                fileToWrite.createNewFile();

                try (FileOutputStream out = new FileOutputStream(fileToWrite)) {
                    Files.deleteIfExists(fileToWrite.toPath());
                    Files.copy(file.getInputStream(), fileToWrite.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }

                logger.info("File saved under: " + Paths.get(filePath).toAbsolutePath().toString());
            }
        }
    }
    
    public void deleteFile(Long patientId, Long fileId) throws UnauthorizedUserException, ItemNotFoundException, FileNotFoundException, IOException {
    	if (entitlementValidator.isEntitled(patientId, Patient.class)) {
    		FileEntry fileEntry = fileRepository.findOne(fileId);
    		
    		if (fileEntry == null) {
    			throw new ItemNotFoundException();
    		}
    		if (!fileEntry.getFileOwner().getId().equals(patientId)) {
    			throw new UnauthorizedUserException();
    		}
    		
    		/* Delete the file entry and information about sharing that file with doctors */
    		
    		Patient patient = patientRepository.findOne(patientId);
    		patient.getFileEntries().removeIf(f -> fileId.equals(f.getId()));
    		patientRepository.save(patient);

    		sharedFileRepository.deleteInBatch(fileEntry.getSharedFiles());
    		fileRepository.delete(fileId);

    		/* Remove the file from the local file system */
    		
            File fileToDelete = Paths.get(fileEntry.getPath()).toAbsolutePath().toFile();
            try (FileOutputStream out = new FileOutputStream(fileToDelete)) {
                Files.deleteIfExists(fileToDelete.toPath());
            }
    	}
    }

    public void shareFile(Long patientId, SharedFileDetails sharedFileDetails) throws UnauthorizedUserException {
        if (entitlementValidator.isEntitled(patientId, Patient.class)) {
            Reservation reservation = reservationRepository.findOne(sharedFileDetails.getReservationId());

            if (reservation == null) {
                throw new UnauthorizedUserException();

            } else if (!patientId.equals(reservation.getPatient().getId())) {
                throw new UnauthorizedUserException();

            } else {

                FileEntry fileEntry = fileRepository.findOne(sharedFileDetails.getFileEntryId());

                if (fileEntry == null) {
                    throw new UnauthorizedUserException();

                } else {
                    SharedFile sharedFile = new SharedFile();
                    sharedFile.setReservation(reservation);
                    sharedFile.setFileEntry(fileEntry);

                    reservation.getSharedFiles().add(sharedFile);
                    sharedFileRepository.save(sharedFile);
                    reservationRepository.save(reservation);
                }
            }
        }
    }
    
    public List<BasicSharedFileData> getSharedFilesForPatient(Long patientId) throws UnauthorizedUserException, UserNotFoundException {
    	if (entitlementValidator.isEntitled(patientId, Patient.class)) {
    		Patient patient = patientRepository.findOne(patientId);
    		
    		if (patient == null) {
    			throw new UserNotFoundException();
    		}
    		
    		return patient.getFutureReservations()
    				.stream()
    				.flatMap(r -> r.getSharedFiles().stream())
    				.map(BasicSharedFileData::new)
    				.collect(Collectors.toList());
    		
    	} else {
    		
    		/* Compiler demands that the method returns a list. */
    		return new ArrayList<>();
    	}
    }
    
    public void cancelShareByFileIdAndReservationId(Long patientId, Long fileId, Long reservationId) throws UnauthorizedUserException, ItemNotFoundException {
    	if (entitlementValidator.isEntitled(patientId, Patient.class)) {
    		SharedFile sharedFile = sharedFileRepository.findByFileEntryIdAndReservationId(fileId, reservationId);
    		
    		if (sharedFile == null) {
    			throw new ItemNotFoundException();
    		}
    		
    		sharedFileRepository.delete(sharedFile);
    	}
    }

    public List<BasicReservationData> getFutureReservations(Long patientId) throws UnauthorizedUserException {
        if (entitlementValidator.isEntitled(patientId, Patient.class)) {
            return patientRepository.findOne(patientId)
                    .getFutureReservations()
                    .stream()
                    .map(BasicReservationData::new)
                    .collect(Collectors.toList());

        } else {
        	
        	/* Compiler demands that the method returns a list. */
            return new ArrayList<>();
        }
    }

    public void bookReservation(Long patientId, Long reservationId) throws UnauthorizedUserException, ReservationTakenException {
    	if (entitlementValidator.isEntitled(patientId, Patient.class)) {
    		Reservation reservation = reservationRepository.findOne(reservationId);

    		if (reservation.getReservationState() != ReservationState.UNRESERVED) {
    			throw new ReservationTakenException();
    		} 
    		
    		reservation.setReservationState(ReservationState.RESERVED);
    		Patient patient = patientRepository.findOne(patientId);
    		reservation.setPatient(patient);
    		patient.getReservations().add(reservation);
    		
    		reservationRepository.save(reservation);
    		patientRepository.save(patient);
    	}
    }
    
    public void cancelReservation(Long patientId, Long reservationId) throws UnauthorizedUserException, ReservationNotFoundException {
    	if (entitlementValidator.isEntitled(patientId, Patient.class)) {
    		Reservation reservation = reservationRepository.findOne(reservationId);
    		
    		if (reservation == null) {
    			throw new ReservationNotFoundException();
    		}
    		if (!reservation.getPatient().getId().equals(patientId)) {
    			throw new UnauthorizedUserException();
    		}
    		
    		reservation.setPatient(null);
    		reservation.setReservationState(ReservationState.UNRESERVED);
    		
    		Patient patient = patientRepository.findOne(patientId);
    		patient.getReservations().removeIf(r -> reservationId.equals(r.getId()));
    		
    		/* Delete shared file entries attached to the reservation being cancelled */
    		sharedFileRepository.deleteInBatch(reservation.getSharedFiles());
    		reservation.setSharedFiles(new ArrayList<>());
    		
    		reservationRepository.save(reservation);
    		patientRepository.save(patient);
    	}
    }

    public void changePersonalData(Long patientId, BasicUserData patientData) throws UnauthorizedUserException {
		Patient patient = patientRepository.findOne(patientId);
        
        if (patient == null) {
        	throw new UnauthorizedUserException();
        	
        } else if(passwordEncoder.matches(
        			patientData.getOldPassword(), patient.getPassword())
        		&& patientData.getNewPassword1() != null 
        		&& patientData.getNewPassword1().equals(patientData.getNewPassword2())){
        		
    		patient.setEmail(patientData.getUsername());
    		patient.setFirstName(patientData.getFirstName());
    		patient.setLastName(patientData.getLastName());
    		patient.setPassword(passwordEncoder.encode(patientData.getNewPassword1()));
            
    		patientRepository.save(patient);
    		
        } else{
            throw new NotMatchedPasswordException();
        }
    }

}
