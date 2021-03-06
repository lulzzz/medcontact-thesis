'use strict';

myApp.config(['$routeProvider', function ($routeProvider) {
    $routeProvider.when('/add-schedule', {
        templateUrl: 'views/doctors/add-schedule/add-schedule.html',
        controller: 'AddScheduleCtrl'
    });
}]);

var isValidDate = function (date) {
    if (Object.prototype.toString.call(date) === "[object Date]") {
    	
        // it is a date
        if (isNaN(date.getTime())) {  // d.valueOf() could also work
            console.error("date is not valid");
            return false;
        }
        else {
            // date is valid
            return true;
        }
    }
    else {
        console.error("[ERROR]: It is not a date");
        return false;
    }
};

myApp.controller('AddScheduleCtrl', ['REST_API', "$rootScope", '$scope', '$http', '$location', 'UserService', 'TimeService',
    function (REST_API, $rootScope, $scope, $http, $location, UserService, TimeService) {
        $rootScope.userDetails = UserService.getUserOrRedirect($location, "/login");
        getSchedule();
        subscribeForNotifications();
        $scope.emptySchedule = false;
        moment.locale('pl');
        
        function subscribeForNotifications() {
	    	var socket = new SockJS(REST_API + "ws")
			var stompClient = Stomp.over(socket);
	    	$rootScope.stompClient = stompClient;
	    	stompClient.debug = null;
	    	$rootScope.ringTone = new Audio("assets/sounds/ring.mp3");
	    	
	    	stompClient.connect({}, function (frame) {
	    		$rootScope.subscribed = true;
	    		
	    		$rootScope.subscription = stompClient.subscribe("/queue/doctors/" + $rootScope.userDetails.id + "/calling", function (message) {
	    			$rootScope.prevPatient = $rootScope.callingPatient;
					$rootScope.callingPatient = JSON.parse(message.body);
					$rootScope.ringTone.play();
					$rootScope.patientCalling = true;
					$rootScope.currentReservation = $rootScope.callingPatient.reservation;
					
					var dialog = $("#modal-calling");
					$("#calling-patient-id").text($rootScope.callingPatient.id);
					$("#calling-patient-name").text($rootScope.callingPatient.name);
					
					$("#calling-patient-start").text(TimeService.parseTimeWithTimezone($scope.callingPatient.reservation.startDateTime));
					$("#redirect-to-consultation-btn").one("click", function () {
						dialog.modal("hide");
						$rootScope.ringTone.pause();
						$rootScope.ringTone.currentTime = 0;
						
						if ($location.url() !== "/doctor/consultation") {
							$location.path("/doctor/consultation");
						}
					});
					
					dialog.modal("show");
	    		});
	    	}, 
	    	{
				id: $rootScope.userDetails.id
			});
	    }
        
        /* Current and previous reservation data controller */
        
        function getNextReservation(reservationId) {
    		$http.get(REST_API + "doctors/" + $rootScope.userDetails.id + "/reservations/" + reservationId + "/next")
    		    .then(function successCallback(response) {
    		    	
    		    	if (response.data.id > 0) {
    		    		$rootScope.nextReservation = response.data;
    		    		$rootScope.nextReservation.startDateTime = TimeService.parseWithTimezone($rootScope.nextReservation.startDateTime);
    		    		$rootScope.nextReservation.endDateTime = TimeService.parseWithTimezone($rootScope.nextReservation.endDateTime);
    		    	
    		    	} else {
    		    		$rootScope.nextReservation = null;
    		    	}
    		    	
    		    }, function errorCallback(response) {
    		    	alert("[ERROR]: Couldn't load current reservation data");
    		    	$location.url("/login");
    		    });
        }

        function getSchedule() {
            $http.get(REST_API + "doctors/" + $rootScope.userDetails.id + "/reservations")
                .then(function successCallback(response) {
                		
                        response.data.forEach(function (schedule) {
                        	schedule.day = moment(schedule.startDateTime).format("DD MM YYYY");
                            schedule.startDateTime = TimeService.parseWithTimezone(schedule.startDateTime);
                            schedule.endDateTime = TimeService.parseWithTimezone(schedule.endDateTime);
                        });
                        
                        response.data = _.groupBy(response.data, function (schedule) {
                            return schedule.day;
                        });

                        $scope.schedules = [];
                        for (var key in response.data) {
                            if (response.data.hasOwnProperty(key)) {
                                $scope.schedules.push({key:new Date(moment(key, "DD MM YYYY")), values:response.data[key]})
                            }
                        }

                        if (!(typeof $scope.schedules !== 'undefined' && $scope.schedules.length > 0)) {
                            $scope.emptySchedule = true;
                        }
                    },
                    function errorCallback(response) {
                        console.log("[ERROR]: " + response.data.message);
                    }
                )
        }


        $scope.addSchedule = function () {
            var start = new Date(moment($scope.date + " " + $scope.startTime, "D MMMM YYYY H:mm"));
            var end = new Date(moment($scope.date + " " + $scope.endTime, "D MMMM YYYY H:mm"));

            if (isValidDate(start) && isValidDate(end)) {
                $http.post(REST_API + "doctors/" + $rootScope.userDetails.id + "/reservation", {
                    start: start,
                    end: end
                })
                    .then(function successCallback(response) {
                        getSchedule();
                        $scope.emptySchedule = false;
                        console.log("Success");
                    }, function errorCallback(response) {
                        console.log("[ERROR]: " + response.data.message);
                    })
            }
            else {
                console.error("Wrong data", $scope.date, $scope.startTime, $scope.endTime)
            }

        };
        
        $(function () {
            $('#datePickerDiv').datetimepicker({
                locale: 'pl',
                format: 'D MMMM YYYY',
                minDate:Date.now(),
            }).on("dp.change", function () {
                $scope.date = $("#datePicker").val();
            });

            $('#startTimePickerDiv').datetimepicker({
                locale: 'pl',
                format: 'H:mm',
                maxDate:(new Date()).setHours(23, 59, 59, 0)
            }).on("dp.change", function (e) {
                $scope.startTime = $("#startTimePicker").val();
                $('#endTimePickerDiv').data("DateTimePicker").minDate(e.date)
            });

            $('#endTimePickerDiv').datetimepicker({
                locale: 'pl',
                format: 'H:mm',
                minDate:Date.now(),
                maxDate:(new Date()).setHours(23, 59, 59, 0)
            }).on("dp.change", function (e) {
                $scope.endTime = $("#endTimePicker").val();
                $('#startTimePickerDiv').data("DateTimePicker").maxDate(e.date);
            });
        });
    }]);