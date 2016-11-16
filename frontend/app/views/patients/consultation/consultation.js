'use strict';

myApp.config(['$routeProvider', function ($routeProvider) {
    $routeProvider.when('/patient-consultation', {
        templateUrl: 'views/patients/consultation/consultation.html',
        controller: 'ConsultationPatientCtrl'
    });
}]);

myApp.controller('ConsultationPatientCtrl', ['REST_API', "$rootScope", '$scope', '$http', '$location', 'UserService',
  function (REST_API, $rootScope, $scope, $http, $location, UserService) {
	$rootScope.userDetails = UserService.getUserOrRedirect($location, "/reservation");

	$http.get(REST_API + "patients/" + $rootScope.userDetails.id + "/connection/" + $rootScope.reservation.id)
	    .then(function successCallback(response) { 
	    	$scope.connectionDetails = response.data;
	    	startConsutation();
	    	
	    }, function errorCallback(response) {
	    	console.log("[ERROR]: Couldn't load connection data");
	    });
	
    var webrtc = {};
    var remotes = {
    	 volume: 0.5
    };
    var peerConnectionConfig;
    
    function startConsutation() {
    	if ($scope.connectionDetails === undefined
        		|| $scope.connectionDetails === null) {
            console.log("Invalid connection data");
            return;

        } else {
            peerConnectionConfig = getCredentials($scope.connectionDetails, initConnection);
        }
    }

    function initConnection(connectionDetails, peerConnectionConfig) {

        /* list of available STUN/TURN servers has been obtained
         * now we will create a SimpleWebRTC instantion based on
         * the received configuration data. */

        webrtc = new SimpleWebRTC({
            localVideoEl: "localVideo",
            remoteVideosEl: "remoteVideos",
            autoRequestMedia: true,
            debug: false,
            detectSpeakingEvents: true,
            autoAdjustMic: false,

            // Add the new peerConnectionConfig object
            peerConnectionConfig: peerConnectionConfig
        });

        webrtc.on("localStream", function () {
            $("#call-btn").prop("disabled", false);
        });

        webrtc.on("readyToCall", function () {
            console.log("Connected to [" + connectionDetails.room + "]");
        });

        webrtc.on("videoAdded", function (video, peer) {
            $("#videosSection")
                .css("border", "none")
                .css("width", "auto")
                .css("height", "auto");
            console.log("" + peer + " has joined the room");
            updateVolumeLevel();
        });

        /* disconnect and leave the room */

        webrtc.on("localScreenRemoved", function (video) {
            $("#localVideo").css("background-color", "white");
            disconnect(webrtc, connectionDetails);
        });
        
        webrtc.on("message", function(message) {
        	console.log(message);
        });

        $("#call-btn").click(function () {
            $(this).prop("disabled", true);
            $("#disconnect-btn").prop("disabled", false);
            
            webrtc.joinRoom(connectionDetails.room);
            webrt.sendToAll("chat", {
            	patient: $rootScope.userDetails,
            	reservation: $rootScope.reservation
            });
            startTransmission(webrtc);
        });

        $("#disconnect-btn").click(function () {
            $("#disconnect-btn").prop("disabled", true);
            disconnect(webrtc, connectionDetails);
        });
        
        $("#mute-btn").click(function () {

            if (remotes.volume == 0.0) {
            	remotes.volume = 0.5;
                webrtc.unmute();
                $(this).removeClass("glyphicon-volume-off");
                $(this).addClass("glyphicon-volume-up");
                $("#volume-level-range").val(remotes.volume * 100);

            } else {
            	remotes.volume = 0.0;
                webrtc.mute();
                $(this).removeClass("glyphicon-volume-up");
                $(this).addClass("glyphicon-volume-off");
                $("#volume-level-range").val(0);
            }
            
            setRemoteVolumeLevel(remotes.volume);
        });

        /* Note that the input of the slider is an integer
         * between 0 and 100. */

        $("#volume-level-range").change(updateVolumeLevel);

        $("#screenshot-btn").click(function () {
        });

        $("#fullscreen-btn").click(function () {

            /* Note that we use the document.getElementById
             * function. It is necessary to obtain the video
             * element this way if we want to call the
             * *-RequestFullScreen function. */

        	var video = document.getElementById("remoteVideos").children[0];
            var resizeFunction = video.requestFullscreen
                || video.webkitRequestFullScreen
                || video.mozRequestFullScreen
                || video.msRequestFullscreen;

            if (resizeFunction !== "undefined") {
                resizeFunction.call(video);
            }
        });
    }

    function getCredentials(connectionDetails, callback) {
        var peerConnectionConfig;
        console.log("Attempting to connect to the room with ID: ["
            + connectionDetails.room + "]...");
      
        console.log(connectionDetails);
        
        $.ajax({
            url: connectionDetails.iceEndpoint,
            data: connectionDetails,
            success: function (data, status) {
            	console.log(data);
                peerConnectionConfig = data.d;

                /* data.d is where the iceServers object lives */

                if (!peerConnectionConfig) {
                    alert("[ERROR]: Connection attempt has failed");
                    
                } else {
                    console.log(peerConnectionConfig);
                    callback(connectionDetails, peerConnectionConfig);
                }
            },
            error: function () {
            	console.log("[Error]: TURN server error");
            }
        });
    }

    function toggleEnabled(element) {
        if (element.hasClass("enabled")) {
            element.removeClass("enabled");
            element.addClass("disabled");

        } else if (element.hasClass("disabled")) {
            element.removeClass("disabled");
            element.addClass("enabled");
        }
    }

    function setRemotePeerVideosEnabled(webrtc, value) {
        var peers = webrtc.getPeers();

        for (var p in peers) {
            var streams = peers[p].pc.getRemoteStreams();

            for (var s in streams) {
                var tracks = streams[s].getTracks();
                for (var t in tracks) {
                    tracks[t].enabled = value;
                }
            }
        }
    }

    function startTransmission(webrtc) {
        if (webrtc !== "undefined") {
            console.log("Transmission started");
            webrtc.unmute();
            webrtc.startLocalVideo();
            setRemotePeerVideosEnabled(webrtc, true);
        }
    }

    function stopTransmission(webrtc) {
        if (webrtc !== "undefined") {
            console.log("Transmission stopped");
            webrtc.mute();
            webrtc.stopLocalVideo();
            setRemotePeerVideosEnabled(webrtc, false);
        }
    }

    function disconnect(webrtc, connectionDetails) {
        if (webrtc !== "undefined") {
            stopTransmission(webrtc);
            webrtc.leaveRoom();

            console.log("Disconnected from: [" + connectionDetails.room + "]");
        }
    }
    
    function updateVolumeLevel() {
    	var muteBtn = $("#mute-btn");
    	remotes.volume = $("#volume-level-range").val() / 100.0;

        setRemoteVolumeLevel(remotes.volume);
        
        if (remotes.volume > 0
        		&& muteBtn.hasClass("glyphicon-volume-off")) {
            webrtc.unmute();
            muteBtn.removeClass("glyphicon-volume-off");
            muteBtn.addClass("glyphicon-volume-up");

        } else if (remotes.volume == 0
        		&& muteBtn.hasClass("glyphicon-volume-up")) {
            webrtc.mute();
            muteBtn.removeClass("glyphicon-volume-up");
            muteBtn.addClass("glyphicon-volume-off");
        }
    }
    
    function setRemoteVolumeLevel(volume) {
        console.log("Volume changed to: " + remotes.volume);
        
    	$("#remoteVideos video").each(function (index, element) {
    		$(element).attr("volume", volume);
    	});
    }
}]);