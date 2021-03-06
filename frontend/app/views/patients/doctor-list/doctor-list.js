'use strict';

myApp.config(['$routeProvider', function ($routeProvider) {
    $routeProvider.when('/doctor-list', {
        templateUrl: 'views/patients/doctor-list/doctor-list.html',
        controller: 'DoctorListCtrl'
    });
}]);

myApp.controller('DoctorListCtrl', ['REST_API', "$rootScope", '$scope', '$http', '$location', 'UserService',
    function (REST_API, $rootScope, $scope, $http, $location, UserService) {
		$rootScope.userDetails = UserService.getUserOrRedirect($location, "/login");
		$scope.sortField = "specialties";
		$scope.sortReversed = false;
		
        getDoctors();

        function getDoctors() {
            $http.get(REST_API + "doctors")
                .then(function successCallback(response) {
                        $scope.doctors = response.data;
                        
                        $scope.doctors.forEach(function (item, index) {
                        	item.specialties = item.specialties.sort();
                        	item.specialties = item.specialties.join(", ");
                        	item.fullName = item.firstName + " " + item.lastName;
                        });
                        
                    }, function errorCallback(response) {
                        console.log("[ERROR]: " + response.data.message);
                    }
                );
        }

        $scope.showTerm = function(doctor){
            $rootScope.fullDoctorName = doctor.firstName + " " + doctor.lastName;
            $location.url("/available-schedule/" + doctor.id);
        }
        
        $scope.showDoctorInfo = function (doctor) {
        	$scope.selectedDoctorInfo = doctor;
        	console.log($scope.selectedDoctorInfo);
        	
        	var dialog = $("#modal-doctor-info");
			
			$("#modal-hide-btn").one("click", function () {
				dialog.modal("hide");
			});
			
			dialog.modal("show");
        }
    }]);